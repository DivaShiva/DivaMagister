package net.botwithus;


import net.botwithus.internal.scripts.ScriptDefinition;
import net.botwithus.rs3.events.impl.ServerTickedEvent;
import net.botwithus.rs3.events.impl.ChatMessageEvent;
import net.botwithus.rs3.game.Client;
import net.botwithus.rs3.game.hud.interfaces.Interfaces;
import net.botwithus.rs3.game.inventories.Backpack;
import net.botwithus.rs3.game.js5.types.vars.VarDomainType;
import net.botwithus.rs3.game.queries.builders.components.ComponentQuery;
import net.botwithus.rs3.game.queries.builders.objects.SceneObjectQuery;
import net.botwithus.rs3.game.queries.results.EntityResultSet;
import net.botwithus.rs3.game.hud.interfaces.Component;
import net.botwithus.rs3.game.scene.entities.characters.player.LocalPlayer;
import net.botwithus.rs3.game.queries.builders.characters.NpcQuery;
import net.botwithus.rs3.game.minimenu.MiniMenu;
import net.botwithus.rs3.game.minimenu.actions.ComponentAction;
import net.botwithus.rs3.game.actionbar.ActionBar;
import net.botwithus.rs3.game.movement.Movement;
import net.botwithus.rs3.game.Coordinate;
import net.botwithus.rs3.game.inventories.Equipment;

import java.util.stream.Collectors;
import net.botwithus.rs3.game.scene.entities.object.SceneObject;
import net.botwithus.rs3.game.vars.VarManager;
import net.botwithus.rs3.script.Execution;
import net.botwithus.rs3.script.LoopingScript;
import net.botwithus.rs3.script.config.ScriptConfig;
import net.botwithus.rs3.game.queries.builders.items.GroundItemQuery;
import net.botwithus.rs3.game.queries.builders.items.InventoryItemQuery;
import net.botwithus.rs3.game.queries.results.ResultSet;
import net.botwithus.rs3.game.scene.entities.item.GroundItem;
import net.botwithus.rs3.game.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkeletonScript extends LoopingScript {

    private BotState botState = BotState.IDLE;
    private boolean someBool = true;
    private boolean useVulnBombs = true; // Default enabled
    private boolean useDeathMark = true; // Default enabled
    private boolean useAdrenalineRenewal = true; // Default enabled
    private boolean useWeaponPoison = true; // Default enabled
    private boolean usePocketSlot = true; // Default enabled
    private boolean useFamiliar = true; // Default enabled
    private boolean useEssenceOfFinality = true; // Default enabled
    private boolean useWeaponSpecial = true; // Default enabled
    private boolean useDeathSkulls = true; // Default enabled
    private Random random = new Random();
    private boolean useSplitSoul = false; // Default disabled
    private int lastLoggedClientCycle = 0;
    private int serverTicks = 0;
    private int livingDeathActivatedServerTick = -1;
    private int lastAbilityServerTick = 0;
    private RotationManager rotation;
    private int killCount = 0;
    private long scriptStartTime = 0;
    private boolean hasInteractedWithLootAll = false;
    private int cumulativeLootValue = 0;
    private boolean deathMarkAppliedThisKill = false;
    private boolean overloadCheckedThisKill = false;
    private boolean weaponPoisonCheckedThisKill = false;
    private boolean pocketSlotActivatedThisKill = false;
    private boolean familiarCheckedThisKill = false;
    private boolean hasTeleported = false;
    private boolean hasLoadedPreset = false;
    private long presetLoadedTime = 0;
    private long invokeDeathCastTime = 0; // Track when Invoke Death was last cast (for 12 second buff duration)
    private boolean shouldCastInvokeDeathAndClickObelisk = false; // Flag to handle completion in main loop

    enum BotState {
        IDLE,
        DETERMINING_STATE,
        TOUCHING_OBELISK,
        HANDLING_DIALOG,
        FIGHTING_MAGISTER,
        WAITING_FOR_LOOT,
        LOOTING,
        BANKING
    }

    public SkeletonScript(String s, ScriptConfig scriptConfig, ScriptDefinition scriptDefinition) {
        super(s, scriptConfig, scriptDefinition);
        this.sgc = new SkeletonScriptGraphicsContext(getConsole(), this);
        
        // Initialize improvise rotation
        rotation = new RotationManager("Necromancy Improvise", true); // true = spend adrenaline
        rotation.setDebug(true);
        rotation.setLogger(this::println); // Use script's println for logging
        rotation.setUseAdrenalineRenewal(useAdrenalineRenewal); // Initialize setting
        rotation.setUseSplitSoul(useSplitSoul); // Initialize setting
        rotation.setUseLivingDeath(false); // Initialize setting
        rotation.setUseEssenceOfFinality(useEssenceOfFinality); // Initialize setting
        rotation.setUseWeaponSpecial(useWeaponSpecial); // Initialize setting
        rotation.setUseDeathSkulls(useDeathSkulls); // Initialize setting
        
        // Subscribe to server tick events
        subscribe(ServerTickedEvent.class, event -> {
            try {
                serverTicks = event.getTicks();
                rotation.setServerTick(serverTicks);
                
                // Only execute rotation/logging when script is active AND in FIGHTING_MAGISTER state
                if (isActive() && botState == BotState.FIGHTING_MAGISTER) {
                    checkAndLog();
                    executeRotation();
                }
            } catch (Exception e) {
                println("[ERROR] Exception in server tick handler: " + e.getMessage());
                e.printStackTrace();
            }
        });
        
        // Subscribe to chat message events
        subscribe(ChatMessageEvent.class, chatMessageEvent -> {
            try {
                String message = chatMessageEvent.getMessage();
                
                // Check if message contains "Completion Time:" (boss kill complete)
                // Note: Case sensitive, and RS3 uses "Time" not "time"
                if (message != null && message.contains("Completion Time:")) {
                    println("[CHAT] *** DETECTED KILL COMPLETION MESSAGE ***");
                    println("[CHAT] Message: " + message);
                    
                    // Trigger if script is active (can be in any state when kill completes)
                    if (isActive()) {
                        handleCompletionTimeMessage();
                    } else {
                        println("[CHAT] Script not active, ignoring completion message");
                    }
                }
            } catch (Exception e) {
                println("[ERROR] Exception in chat message handler: " + e.getMessage());
                e.printStackTrace();
            }
        });
        
        println("[INIT] Chat message event listener registered successfully");
    }
    
    private void executeRotation() {
        // Only execute when in FIGHTING_MAGISTER state
        if (botState != BotState.FIGHTING_MAGISTER) {
            return;
        }
        
        // Check if player has a target
        LocalPlayer player = Client.getLocalPlayer();
        if (player == null || player.getTarget() == null) {
            return; // No target, don't execute rotation
        }
        
        // Check prayer points and drink restore if needed
        checkAndDrinkPrayerPotion(player);
        
        // Verify Magister actually exists and is alive (prevents using abilities on dead boss)
        EntityResultSet<net.botwithus.rs3.game.scene.entities.characters.npc.Npc> results = 
            NpcQuery.newQuery()
                .name("The Magister", "The Magister (level: 899)")
                .option("Attack")
                .results();
        
        if (results.isEmpty()) {
            // Boss is dead but state hasn't updated yet, don't use abilities
            return;
        }
        
        // Additional check: verify boss has health
        net.botwithus.rs3.game.scene.entities.characters.npc.Npc magister = results.first();
        if (magister == null || magister.getCurrentHealth() <= 0) {
            // Boss is dead or dying, don't use abilities
            return;
        }
        
        // Execute every 3 server ticks (1.8 seconds)
        if (serverTicks - lastAbilityServerTick >= 3) {
            // HIGHEST PRIORITY: Check and apply Death Mark if enabled
            // Only apply once per kill, and only if Invoke Death buff is not active
            if (useDeathMark && !deathMarkAppliedThisKill) {
                // Check if Invoke Death buff is still active (12 second duration)
                long timeSinceInvokeDeath = System.currentTimeMillis() - invokeDeathCastTime;
                boolean invokeDeathBuffActive = timeSinceInvokeDeath < 12000; // 12 seconds
                
                if (invokeDeathBuffActive) {
                    // Buff is active, mark as applied and skip rotation manager's ensureDeathMarked
                    println("[ROTATION] Invoke Death buff active (" + (12 - timeSinceInvokeDeath / 1000) + "s remaining), skipping recast");
                    deathMarkAppliedThisKill = true;
                } else {
                    // Buff expired, let rotation manager apply it
                    boolean deathMarkUsed = rotation.ensureDeathMarked();
                    if (deathMarkUsed) {
                        deathMarkAppliedThisKill = true;
                        invokeDeathCastTime = System.currentTimeMillis(); // Track cast time
                        lastAbilityServerTick = serverTicks;
                        println("Tick " + serverTicks + " - Using: Invoke Death");
                        return; // Skip normal rotation this tick
                    }
                }
            }
            
            // Check and apply vulnerability if enabled
            if (useVulnBombs) {
                rotation.ensureVulned();
            }
            
            // Execute normal rotation
            if (rotation.execute()) {
                lastAbilityServerTick = serverTicks;
                String ability = rotation.getLastAbilityUsed();
                println("Tick " + serverTicks + " - Using: " + ability);
            }
        }
    }
    
    private void checkAndLog() {
        try {
            // Only log when in FIGHTING_MAGISTER state
            if (botState != BotState.FIGHTING_MAGISTER) {
                return;
            }
            
            int currentCycle = Client.getClientCycle();
            
            // Log every 10 game ticks (6 seconds at 0.6s per tick) to reduce query frequency
            if (lastLoggedClientCycle == 0 || currentCycle - lastLoggedClientCycle >= 10) {
                logNecromancyStatus(currentCycle);
                lastLoggedClientCycle = currentCycle;
            }
        } catch (Exception e) {
            println("[ERROR] Exception in checkAndLog: " + e.getMessage());
        }
    }
    
    private void logNecromancyStatus(int clientCycle) {
        try {
            // Target debuffs using varbits
            boolean vulned = false;
            boolean deathMarked = false;
            boolean bloated = false;
            int livingDeathVarValue = -1;
            
            try {
                vulned = VarManager.getVarbitValue(1939) == 1;
                deathMarked = VarManager.getVarbitValue(53247) == 1;
                bloated = VarManager.getVarbitValue(53245) == 1;
                livingDeathVarValue = VarManager.getVarValue(VarDomainType.PLAYER, 11059);
            } catch (Exception e) {
                println("[ERROR] Exception querying varbits in logNecromancyStatus: " + e.getMessage());
                return; // Skip logging if we can't get the data
            }
            
            StringBuilder active = new StringBuilder();
            if (vulned) active.append("Vulnerability, ");
            if (bloated) active.append("Bloat, ");
            if (deathMarked) active.append("Death Mark, ");
            if (active.length() > 0) {
                active.setLength(active.length() - 2);
            } else {
                active.append("None");
            }
            
            // Living Death status (-1 if inactive, tick number if active)
            boolean livingDeath = livingDeathVarValue != -1;
            
            // Track when Living Death becomes active
            if (livingDeath && livingDeathActivatedServerTick == -1) {
                livingDeathActivatedServerTick = serverTicks;
            } else if (!livingDeath) {
                livingDeathActivatedServerTick = -1;
            }
            
            println("Client Cycle: " + clientCycle + " | Server Ticks: " + serverTicks);
            println("Target Debuffs: " + active);
            if (livingDeath) {
                int ticksActive = serverTicks - livingDeathActivatedServerTick;
                println("Living Death: ACTIVE (server tick started: " + livingDeathActivatedServerTick + ", active for: " + ticksActive + " server ticks)");
            } else {
                println("Living Death: Inactive");
            }
        } catch (Exception e) {
            println("[ERROR] Exception in logNecromancyStatus: " + e.getMessage());
        }
    }

    @Override
    public void onLoop() {
        LocalPlayer player = Client.getLocalPlayer();
        if (player == null || Client.getGameState() != Client.GameState.LOGGED_IN) {
            Execution.delay(random.nextLong(1000, 2000));
            return;
        }
        
        if (botState == BotState.IDLE) {
            Execution.delay(random.nextLong(1000, 2000));
            return;
        }
        
        switch (botState) {
            case IDLE -> {
                println("Idle - waiting to start");
                Execution.delay(random.nextLong(1000, 2000));
            }
            case DETERMINING_STATE -> {
                Execution.delay(handleDeterminingState(player));
            }
            case TOUCHING_OBELISK -> {
                Execution.delay(handleTouchObelisk(player));
            }
            case HANDLING_DIALOG -> {
                Execution.delay(handleDialog(player));
            }
            case FIGHTING_MAGISTER -> {
                Execution.delay(handleFighting(player));
            }
            case WAITING_FOR_LOOT -> {
                Execution.delay(handleWaitingForLoot(player));
            }
            case LOOTING -> {
                Execution.delay(handleLooting(player));
            }
            case BANKING -> {
                Execution.delay(handleBanking(player));
            }
        }
    }

    private long handleDeterminingState(LocalPlayer player) {
        println("[DETERMINING] Checking player location...");
        
        // Check if player can see Soul obelisk
        EntityResultSet<SceneObject> obeliskResults = SceneObjectQuery.newQuery()
                .name("Soul obelisk")
                .option("Touch")
                .results();
        
        if (!obeliskResults.isEmpty()) {
            println("[DETERMINING] Soul obelisk detected - starting at Magister arena");
            setBotState(BotState.TOUCHING_OBELISK);
        } else {
            println("[DETERMINING] No Soul obelisk detected - starting at bank");
            setBotState(BotState.BANKING);
        }
        
        return random.nextLong(600, 1000);
    }

    private long handleTouchObelisk(LocalPlayer player) {
        // Check if player is still moving (loading into arena)
        if (player.isMoving()) {
            println("[MAGISTER] Player is moving, waiting to stop...");
            return random.nextLong(1000, 1500);
        }
        
        // Check if we have a Key to the Crossing
        if (!Backpack.contains("Key to the Crossing")) {
            println("[MAGISTER] No Key to the Crossing found!");
            
            // Check if there's loot on the ground to collect
            var itemsOnFloor = GroundItemQuery.newQuery().results();
            if (!itemsOnFloor.isEmpty()) {
                println("[MAGISTER] Loot remaining on ground, collecting before banking");
                botState = BotState.LOOTING;
                return random.nextLong(600, 1000);
            }
            
            println("[MAGISTER] No keys or loot remaining, going to bank.");
            botState = BotState.BANKING;
            return random.nextLong(600, 1000);
        }
        
        println("[MAGISTER] Looking for Soul obelisk");
        
        // Query for Soul obelisk
        EntityResultSet<SceneObject> results = SceneObjectQuery.newQuery().name("Soul obelisk").option("Touch").results();
        
        if (results.isEmpty()) {
            println("[MAGISTER] Soul obelisk not found");
            return random.nextLong(1000, 2000);
        }
        
        SceneObject obelisk = results.first();
        if (obelisk == null) {
            println("[MAGISTER] Soul obelisk is null");
            return random.nextLong(1000, 2000);
        }
        
        // Check distance to obelisk
        Coordinate obeliskCoord = obelisk.getCoordinate();
        Coordinate playerCoord = player.getCoordinate();
        double distance = playerCoord.distanceTo(obeliskCoord);
        
        println("[MAGISTER] Distance to obelisk: " + String.format("%.1f", distance) + " tiles");
        
        // If too far, walk closer
        if (distance > 15) {
            println("[MAGISTER] Obelisk is too far (" + String.format("%.1f", distance) + " tiles), walking closer...");
            Movement.walkTo(obeliskCoord.getX(), obeliskCoord.getY(), true);
            return random.nextLong(1500, 2000);
        }
        
        println("[MAGISTER] Touching Soul obelisk");
        
        if (obelisk.interact("Touch")) {
            println("[MAGISTER] Successfully touched obelisk, waiting for dialog");
            botState = BotState.HANDLING_DIALOG;
            return random.nextLong(600, 900); // Reduced from 2400-3000
        }
        
        println("[MAGISTER] Failed to touch obelisk");
        return random.nextLong(800, 1200);
    }

    private long handleDialog(LocalPlayer player) {
        println("[MAGISTER] Handling dialog");
        
        // Check if Magister already spawned first (fastest check)
        EntityResultSet<net.botwithus.rs3.game.scene.entities.characters.npc.Npc> results = 
            NpcQuery.newQuery()
                .name("The Magister", "The Magister (level: 899)")
                .option("Attack")
                .results();
        
        if (!results.isEmpty()) {
            net.botwithus.rs3.game.scene.entities.characters.npc.Npc magister = results.first();
            println("[MAGISTER] Magister spawned! Name: " + magister.getName() + " - Switching to fighting");
            // Reset tracking for new kill
            rotation.resetDeathMark();
            deathMarkAppliedThisKill = false;
            overloadCheckedThisKill = false;
            weaponPoisonCheckedThisKill = false;
            pocketSlotActivatedThisKill = false;
            familiarCheckedThisKill = false;
            botState = BotState.FIGHTING_MAGISTER;
            return random.nextLong(300, 600); // Reduced delay
        }
        
        // Check if dialog interface is open (interface 1188)
        boolean dialogOpen = Interfaces.isOpen(1188);
        println("[MAGISTER] Dialog interface 1188 open: " + dialogOpen);
        
        if (dialogOpen) {
            println("[MAGISTER] Dialog found, clicking option with MiniMenu");
            // Use MiniMenu to interact with dialog (ComponentAction.DIALOGUE, 0, -1, 77856776)
            boolean clicked = MiniMenu.interact(ComponentAction.DIALOGUE.getType(), 0, -1, 77856776);
            println("[MAGISTER] MiniMenu.interact result: " + clicked);
            
            if (clicked) {
                println("[MAGISTER] Dialog clicked successfully, waiting for Magister to spawn");
                // Don't change state yet, wait for next loop to detect Magister
                return random.nextLong(400, 700); // Reduced from 800-1200
            } else {
                println("[MAGISTER] MiniMenu.interact failed, trying again");
                return random.nextLong(300, 500); // Reduced from 400-600
            }
        }
        
        // No dialog and no Magister - might be in transition
        println("[MAGISTER] No dialog detected and no Magister found, waiting...");
        return random.nextLong(400, 700); // Reduced from 600-1000
    }

    private long handleFighting(LocalPlayer player) {
        // Check overload buff at start of fight (once per kill)
        if (!overloadCheckedThisKill) {
            drinkOverload();
            overloadCheckedThisKill = true;
        }
        
        // Check weapon poison at start of fight (once per kill)
        if (useWeaponPoison && !weaponPoisonCheckedThisKill) {
            applyWeaponPoison();
            weaponPoisonCheckedThisKill = true;
        }
        
        // Activate pocket slot at start of fight (once per kill)
        if (usePocketSlot && !pocketSlotActivatedThisKill) {
            activatePocketSlot();
            pocketSlotActivatedThisKill = true;
        }
        
        // Manage familiar at start of fight (once per kill)
        if (useFamiliar && !familiarCheckedThisKill) {
            manageFamiliarSummoning();
            familiarCheckedThisKill = true;
        }
        
        // Check if Magister exists
        EntityResultSet<net.botwithus.rs3.game.scene.entities.characters.npc.Npc> results = 
            NpcQuery.newQuery()
                .name("The Magister", "The Magister (level: 899)")
                .option("Attack")
                .results();
        
        if (results.isEmpty()) {
            println("[MAGISTER] Magister dead, transitioning to loot wait");
            botState = BotState.WAITING_FOR_LOOT;
            return random.nextLong(300, 600);
        }
        
        net.botwithus.rs3.game.scene.entities.characters.npc.Npc magister = results.nearest();
        
        // Check if we're targeting the Magister
        if (player.getTarget() == null || !player.getTarget().equals(magister)) {
            println("[MAGISTER] Targeting The Magister");
            if (magister.interact("Attack")) {
                println("[MAGISTER] Successfully targeted Magister");
                return random.nextLong(800, 1200);
            } else {
                println("[MAGISTER] Failed to target Magister");
                return random.nextLong(600, 1000);
            }
        }
        
        // We're fighting, rotation handles combat automatically via ServerTickedEvent
        // Just wait and let the rotation do its thing
        return random.nextLong(600, 1000);
    }

    private long handleWaitingForLoot(LocalPlayer player) {
        // Check if we need to cast Invoke Death and click obelisk (from completion message)
        if (shouldCastInvokeDeathAndClickObelisk) {
            shouldCastInvokeDeathAndClickObelisk = false; // Reset flag
            
            // Check if Invoke Death buff is still active (12 second duration)
            long timeSinceInvokeDeath = System.currentTimeMillis() - invokeDeathCastTime;
            boolean invokeDeathBuffActive = timeSinceInvokeDeath < 12000; // 12 seconds = 12000ms
            
            // Cast Invoke Death only if buff expired (to save time on next kill)
            if (useDeathMark && !invokeDeathBuffActive) {
                println("[COMPLETION] Casting Invoke Death (buff expired " + (timeSinceInvokeDeath / 1000) + "s ago)");
                boolean deathMarkUsed = ActionBar.useAbility("Invoke Death");
                if (deathMarkUsed) {
                    println("[COMPLETION] ✓ Invoke Death cast - buff active for 12 seconds");
                    invokeDeathCastTime = System.currentTimeMillis(); // Track cast time
                    deathMarkAppliedThisKill = false; // Reset for new kill
                    rotation.resetDeathMark();
                } else {
                    println("[COMPLETION] ✗ Failed to cast Invoke Death");
                }
            } else if (useDeathMark && invokeDeathBuffActive) {
                println("[COMPLETION] ✓ Invoke Death buff still active (" + (12 - timeSinceInvokeDeath / 1000) + "s remaining)");
                deathMarkAppliedThisKill = false; // Reset for new kill
                rotation.resetDeathMark();
            }
            
            // Click Soul obelisk for immediate next kill
            println("[COMPLETION] Clicking Soul obelisk to start next kill...");
            EntityResultSet<SceneObject> obeliskResults = SceneObjectQuery.newQuery()
                    .name("Soul obelisk")
                    .option("Touch")
                    .results();
            
            if (!obeliskResults.isEmpty()) {
                SceneObject obelisk = obeliskResults.first();
                
                if (obelisk.interact("Touch")) {
                    println("[COMPLETION] ✓ Obelisk touched - transitioning to HANDLING_DIALOG");
                    botState = BotState.HANDLING_DIALOG;
                    return random.nextLong(600, 900);
                } else {
                    println("[COMPLETION] ✗ Failed to touch obelisk");
                }
            } else {
                println("[COMPLETION] ✗ Soul obelisk not found");
            }
        }
        
        // Check if Magister respawned (shouldn't happen, but safety check)
        EntityResultSet<net.botwithus.rs3.game.scene.entities.characters.npc.Npc> results = 
            NpcQuery.newQuery()
                .name("The Magister", "The Magister (level: 899)")
                .option("Attack")
                .results();
        
        if (!results.isEmpty()) {
            println("[MAGISTER] Magister still alive, back to fighting");
            botState = BotState.FIGHTING_MAGISTER;
            return random.nextLong(600, 1000);
        }
        
        // Check if we should loot (every 3 kills)
        if (killCount % 3 == 0) {
            println("[MAGISTER] 3 kills reached, looting!");
            botState = BotState.LOOTING;
            return random.nextLong(600, 1000);
        }
        
        // Otherwise, go back to touching obelisk
        println("[MAGISTER] Magister dead, restarting cycle");
        // Reset Death Mark for next kill
        rotation.resetDeathMark();
        deathMarkAppliedThisKill = false;
        botState = BotState.TOUCHING_OBELISK;
        return random.nextLong(600, 1000);
    }

        public boolean isUseSplitSoul() {
        return useSplitSoul;
    }
    
    public void setUseSplitSoul(boolean useSplitSoul) {
        this.useSplitSoul = useSplitSoul;
        if (rotation != null) {
            rotation.setUseSplitSoul(useSplitSoul);
        }
    }

    public BotState getBotState() {
        return botState;
    }

    public void setBotState(BotState botState) {
        this.botState = botState;
        // Reset kill counter and start time when starting
        if (botState == BotState.TOUCHING_OBELISK && killCount == 0) {
            scriptStartTime = System.currentTimeMillis();
        }
    }
    
    public int getKillCount() {
        return killCount;
    }
    
    public double getKillsPerHour() {
        long elapsedTime = System.currentTimeMillis() - scriptStartTime;
        long elapsedSeconds = elapsedTime / 1000;
        
        // Only calculate if at least 10 seconds have passed to avoid division issues
        if (elapsedSeconds >= 10 && killCount > 0) {
            return (killCount / (elapsedSeconds / 3600.0));
        }
        return 0.0;
    }
    
    public void resetKillCount() {
        killCount = 0;
        scriptStartTime = System.currentTimeMillis();
    }
    
    public void startScript() {
        println("[START] Starting script - determining location...");
        setBotState(BotState.DETERMINING_STATE);
    }

    public boolean isSomeBool() {
        return someBool;
    }

    public void setSomeBool(boolean someBool) {
        this.someBool = someBool;
    }
    
    public boolean isUseVulnBombs() {
        return useVulnBombs;
    }
    
    public void setUseVulnBombs(boolean useVulnBombs) {
        this.useVulnBombs = useVulnBombs;
    }
    
    public boolean isUseDeathMark() {
        return useDeathMark;
    }
    
    public void setUseDeathMark(boolean useDeathMark) {
        this.useDeathMark = useDeathMark;
    }
    
    public boolean isUseAdrenalineRenewal() {
        return useAdrenalineRenewal;
    }
    
    public void setUseAdrenalineRenewal(boolean useAdrenalineRenewal) {
        this.useAdrenalineRenewal = useAdrenalineRenewal;
        if (rotation != null) {
            rotation.setUseAdrenalineRenewal(useAdrenalineRenewal);
        }
    }
    
    public boolean isUseWeaponPoison() {
        return useWeaponPoison;
    }
    
    public void setUseWeaponPoison(boolean useWeaponPoison) {
        this.useWeaponPoison = useWeaponPoison;
    }
    
    public boolean isUsePocketSlot() {
        return usePocketSlot;
    }
    
    public void setUsePocketSlot(boolean usePocketSlot) {
        this.usePocketSlot = usePocketSlot;
    }
    
    public boolean isUseFamiliar() {
        return useFamiliar;
    }
    
    public void setUseFamiliar(boolean useFamiliar) {
        this.useFamiliar = useFamiliar;
    }
    
    public boolean isUseEssenceOfFinality() {
        return useEssenceOfFinality;
    }
    
    public void setUseEssenceOfFinality(boolean useEssenceOfFinality) {
        this.useEssenceOfFinality = useEssenceOfFinality;
        if (rotation != null) {
            rotation.setUseEssenceOfFinality(useEssenceOfFinality);
        }
    }
    
    public boolean isUseWeaponSpecial() {
        return useWeaponSpecial;
    }
    
    public void setUseWeaponSpecial(boolean useWeaponSpecial) {
        this.useWeaponSpecial = useWeaponSpecial;
        if (rotation != null) {
            rotation.setUseWeaponSpecial(useWeaponSpecial);
        }
    }
    
    public boolean isUseDeathSkulls() {
        return useDeathSkulls;
    }
    
    public void setUseDeathSkulls(boolean useDeathSkulls) {
        this.useDeathSkulls = useDeathSkulls;
        if (rotation != null) {
            rotation.setUseDeathSkulls(useDeathSkulls);
        }
    }
    
    /**
     * Manually trigger action bar scan
     */
    public void scanActionBar() {
        if (rotation != null) {
            println("=== Scanning Action Bar ===");
            rotation.scanActionBar();
            println("=== Scan Complete ===");
        } else {
            println("[ERROR] Rotation manager not initialized");
        }
    }
    
    /**
     * Reset cached item slots (useful if action bar changes)
     */
    public void resetItemCache() {

        println("[CACHE] Item cache reset");
    }
    
    /**
     * Get count of cached abilities
     */
    public int getCachedAbilityCount() {
        if (rotation != null) {
            return rotation.getCachedAbilityCount();
        }
        return 0;
    }
    
    /**
     * Print all cached ability slots to console
     */
    public void printCachedSlots() {
        if (rotation != null) {
            rotation.printCachedSlots();
        } else {
            println("[ERROR] Rotation manager not initialized");
        }
    }
    
    private long handleLooting(LocalPlayer player) {
        println("[LOOT] Starting loot collection");
        loot();
        return random.nextLong(1000, 2000);
    }
    
    private void loot() {
        hasInteractedWithLootAll = false;
        
        // 1) grab everything on the ground
        var itemsOnFloor = GroundItemQuery.newQuery().results();
        if (itemsOnFloor.isEmpty()) {
            println("[LOOT] No items on floor, continuing");
            rotation.resetDeathMark();
            deathMarkAppliedThisKill = false;
            botState = BotState.TOUCHING_OBELISK;
            return;
        }
        
        // 3) pick one at random to actually take
        GroundItem gi = itemsOnFloor.random();
        if (gi == null) {
            println("[LOOT] No valid ground item, continuing");
            rotation.resetDeathMark();
            deathMarkAppliedThisKill = false;
            botState = BotState.TOUCHING_OBELISK;
            return;
        }
        
        // 4) log which one you're taking
        println("[LOOT] Taking " + gi.getStackSize() + "x " + gi.getName());
        
        // 6) interact
        gi.interact("Take");
        Execution.delay(random.nextLong(1200, 2000));
        
        // 7) retry if the interface didn't open
        boolean interfaceOpened = Execution.delayUntil(
                15_000,
                () -> Interfaces.isOpen(1622)
        );
        if (!interfaceOpened) {
            println("[LOOT] Interface 1622 did not open. Attempting to interact again.");
            if (gi.interact("Take")) {
                println("[LOOT] Retrying take of " + gi.getName());
                Execution.delay(random.nextLong(1800, 3000));
            }
        }
        
        // 8) press "Loot All"
        LootAll();
        
        // 9) update your cumulative-value tracker
        updateAndDisplayCumulativeLootValue();
    }
    
    private void LootAll() {
        if (!hasInteractedWithLootAll) {
            Execution.delay(random.nextLong(500, 1000));
            
            ComponentQuery lootAllQuery = ComponentQuery.newQuery(1622);
            List<Component> components = lootAllQuery.componentIndex(22).results().stream().toList();
            
            if (!components.isEmpty()) {
                Component lootAllComponent = components.get(0);
                if (lootAllComponent.interact()) {
                    println("[LOOT] Clicked 'Loot All' successfully");
                    hasInteractedWithLootAll = true;
                    
                    // Cast Invoke Death AFTER looting (so buff doesn't expire during loot process)
                    if (useDeathMark) {
                        long timeSinceInvokeDeath = System.currentTimeMillis() - invokeDeathCastTime;
                        boolean invokeDeathBuffActive = timeSinceInvokeDeath < 12000; // 12 seconds
                        
                        if (!invokeDeathBuffActive) {
                            println("[LOOT] Casting Invoke Death after looting (buff expired " + (timeSinceInvokeDeath / 1000) + "s ago)");
                            Execution.delay(random.nextLong(300, 600)); // Small delay after loot
                            boolean deathMarkUsed = ActionBar.useAbility("Invoke Death");
                            if (deathMarkUsed) {
                                invokeDeathCastTime = System.currentTimeMillis();
                                println("[LOOT] ✓ Invoke Death cast - buff active for 12 seconds");
                                Execution.delay(random.nextLong(600, 900));
                            } else {
                                println("[LOOT] ✗ Failed to cast Invoke Death");
                            }
                        } else {
                            println("[LOOT] Invoke Death buff still active (" + (12 - timeSinceInvokeDeath / 1000) + "s remaining)");
                        }
                    }
                    
                    // Reset Death Mark and go back to touching obelisk
                    rotation.resetDeathMark();
                    deathMarkAppliedThisKill = false;
                    botState = BotState.TOUCHING_OBELISK;
                } else {
                    println("[LOOT] Failed to click 'Loot All'");
                }
            } else {
                println("[LOOT] 'Loot All' component not found");
            }
        }
    }
    
    private void updateAndDisplayCumulativeLootValue() {
        if (!Interfaces.isOpen(1622)) return;
        Component valueScan = ComponentQuery.newQuery(1622)
                .componentIndex(3)
                .results()
                .last();
        if (valueScan == null) {
            println("[LOOT] Loot-value component not found");
            return;
        }
        
        String detectedString = valueScan.getText();
        String numberWithSuffix = extractNumberWithSuffix(detectedString);
        if ("Error".equals(numberWithSuffix)) return;
        
        try {
            int valueToAdd = parseValueWithSuffix(numberWithSuffix);
            cumulativeLootValue += valueToAdd;
            println("[LOOT] Cumulative Loot Value: " + cumulativeLootValue + "K");
        } catch (NumberFormatException e) {
            println("[LOOT] Number format error: " + e.getMessage());
        }
    }
    
    public String extractNumberWithSuffix(String source) {
        Matcher matcher = Pattern.compile("(?i)([\\d,]+)([KM])")
                .matcher(source);
        if (matcher.find()) {
            // group(1) is the digits+commas, group(2) is the suffix
            return matcher.group(1) + matcher.group(2).toUpperCase();
        }
        return "Error";
    }
    
    private int parseValueWithSuffix(String numberWithSuffix) {
        String clean = numberWithSuffix
                .replace(",", "")
                .toUpperCase();   // e.g. "1843K" or "150M"
        char suffix = clean.charAt(clean.length() - 1);
        int base = Integer.parseInt(clean.substring(0, clean.length() - 1));
        
        return switch (suffix) {
            case 'K' -> base;
            case 'M' -> base * 1000;
            default -> throw new IllegalArgumentException(
                    "Unexpected suffix: " + suffix);
        };
    }
    
    public int getCumulativeLootValue() {
        return cumulativeLootValue;
    }
    
    private void drinkOverload() {
        int buffTicks = VarManager.getVarbitValue(48834);
        if (buffTicks >= 3) {
            // you still have 3+ ticks (45+ seconds) no need to drink yet
            return;
        }
        
        println("[OVERLOAD] Overload buff low (" + buffTicks + ") — searching for Overload potion");
        Item pot = InventoryItemQuery
                .newQuery(93)
                .results()
                .stream()
                .filter(i -> {
                    String name = i.getName();
                    return name != null
                            && name.toLowerCase().contains("overload");
                })
                .findFirst()
                .orElse(null);
        
        if (pot != null) {
            println("[OVERLOAD] Drinking " + pot.getName());
            if (!Backpack.interact(pot.getName(), "Drink")) {
                println("[OVERLOAD] Failed to drink " + pot.getName());
            }
        } else {
            println("[OVERLOAD] No Overload potion found.");
        }
    }
    
    private void applyWeaponPoison() {
        int poisonCharges = VarManager.getVarbitValue(2102);
        if (poisonCharges > 3) {
            // Still have charges, no need to apply
            return;
        }
        
        println("[WEAPON POISON] Poison charges low (" + poisonCharges + ") — searching for Weapon poison");
        
        ResultSet<Item> items = InventoryItemQuery.newQuery(93).results();
        Pattern poisonPattern = Pattern.compile("weapon poison\\+*?", Pattern.CASE_INSENSITIVE);
        Item weaponPoisonItem = items.stream()
                .filter(item -> {
                    if (item.getName() == null) return false;
                    Matcher matcher = poisonPattern.matcher(item.getName());
                    return matcher.find();
                })
                .findFirst()
                .orElse(null);
        
        if (weaponPoisonItem != null) {
            println("[WEAPON POISON] Applying " + weaponPoisonItem.getName() + " ID: " + weaponPoisonItem.getId());
            if (Backpack.interact(weaponPoisonItem.getName(), "Apply")) {
                println("[WEAPON POISON] " + weaponPoisonItem.getName() + " has been applied");
            } else {
                println("[WEAPON POISON] Failed to apply " + weaponPoisonItem.getName());
            }
        } else {
            println("[WEAPON POISON] No Weapon poison found in inventory");
        }
    }
    
    private void activatePocketSlot() {
        // Check if pocket slot item is inactive (0) and has enough time remaining (60+ seconds)
        // Varbit 30605: active status (0 = inactive, 1 = active) - works for Scripture of Jas and similar items
        // Varbit 30604: time remaining in seconds
        int pocketActive = VarManager.getVarbitValue(30605);
        int timeRemaining = VarManager.getVarbitValue(30604);
        
        if (pocketActive == 0 && timeRemaining >= 60) {
            println("[POCKET SLOT] Activating pocket slot item (Time remaining: " + timeRemaining + "s)");
            Equipment.interact(Equipment.Slot.POCKET, "Activate/Deactivate");
        } else if (pocketActive == 1) {
            println("[POCKET SLOT] Pocket slot item already active");
        } else {
            println("[POCKET SLOT] Not enough time remaining (" + timeRemaining + "s < 60s)");
        }
    }
    
    private void manageFamiliarSummoning() {
        boolean isFamiliarSummoned = isFamiliarSummoned();
        int familiarTimeRemaining = VarManager.getVarbitValue(6055);
        
        if (isFamiliarSummoned) {
            familiarTimeRemaining = VarManager.getVarbitValue(6055);
            println("[FAMILIAR] Familiar time remaining: " + familiarTimeRemaining + " Minutes");
        }
        
        if (!isFamiliarSummoned || familiarTimeRemaining <= 5) {
            summonFamiliar();
        }
    }
    
    private boolean isFamiliarSummoned() {
        Component familiarComponent = ComponentQuery.newQuery(284).spriteId(26095).results().first();
        return familiarComponent != null;
    }
    
    private void summonFamiliar() {
        ResultSet<Item> items = InventoryItemQuery.newQuery(93).results();
        Item itemToSummon = items.stream()
                .filter(item -> item.getName() != null && 
                        (item.getName().toLowerCase().contains("pouch") || 
                         item.getName().toLowerCase().contains("contract")))
                .findFirst()
                .orElse(null);
        
        if (itemToSummon != null) {
            println("[FAMILIAR] Attempting to summon with: " + itemToSummon.getName());
            boolean success = Backpack.interact(itemToSummon.getName(), "Summon");
            Execution.delay(random.nextLong(1600, 2100));
            
            if (success) {
                println("[FAMILIAR] Summoned familiar with: " + itemToSummon.getName());
                boolean familiarSummoned = Execution.delayUntil(10000, this::isFamiliarSummoned);
                
                if (familiarSummoned) {
                    println("[FAMILIAR] " + itemToSummon.getName() + " is now summoned.");
                } else {
                    println("[FAMILIAR] Failed to confirm familiar summoning");
                }
            } else {
                println("[FAMILIAR] Failed to interact with " + itemToSummon.getName());
            }
        } else {
            println("[FAMILIAR] No familiar pouch or contract found in inventory");
        }
    }
    
    private void checkAndDrinkPrayerPotion(LocalPlayer player) {
        // Check if prayers are active (Sorrow and Ruination)
        boolean sorrowActive = VarManager.getVarbitValue(53279) == 1;
        boolean ruinationActive = VarManager.getVarbitValue(53280) == 1;
        
        // If both prayers are off, activate Quick-prayers
        if (!sorrowActive && !ruinationActive) {
            println("[PRAYER] Prayers not active (Sorrow: " + sorrowActive + ", Ruination: " + ruinationActive + "), activating Quick-prayers");
            ActionBar.useAbility("Quick-prayers 1");
        }
        
        // Check prayer points and drink restore if needed
        int prayerPoints = player.getPrayerPoints();
        
        if (prayerPoints < 6000) {
            println("[PRAYER] Prayer points low (" + prayerPoints + "/600), drinking Super restore");
            drinkSuperRestore();
        }
    }
    
    private void drinkSuperRestore() {
        println("[PRAYER] Attempting to drink Super Restore from action bar...");
        
        String[] superRestoreVariants = new String[]{
            "Super restore",
            "Super restore flask",
            "Super restore flask (1)",
            "Super restore flask (2)",
            "Super restore flask (3)",
            "Super restore flask (4)",
            "Super restore flask (5)",
            "Super restore flask (6)",
            "Super restore (1)",
            "Super restore (2)",
            "Super restore (3)",
            "Super restore (4)",
        };
        
        for (String restoreName : superRestoreVariants) {
            ResultSet<Item> item = InventoryItemQuery.newQuery(93).name(restoreName).results();
            if (ActionBar.containsItem(restoreName) && !item.isEmpty()) {
                boolean successfulDrink = ActionBar.useItem(restoreName, "Drink");
                if (successfulDrink) {
                    println("[PRAYER] Successfully drank " + restoreName + " from action bar");
                    return;
                } else {
                    println("[PRAYER] Failed to drink " + restoreName + " despite being on action bar");
                }
            }
        }
        
        println("[PRAYER] No Super Restore found on action bar or in inventory");
    }
    

        

    
    /**
     * Handle "Completion Time:" chat message - set flag to process in main loop
     * The Invoke Death buff lasts 12 seconds and will apply Death Mark on next attack
     */
    private void handleCompletionTimeMessage() {
        // Increment kill counter
        killCount++;
        
        // Calculate kills per hour
        long elapsedTime = System.currentTimeMillis() - scriptStartTime;
        long elapsedSeconds = elapsedTime / 1000;
        double killsPerHour = 0.0;
        
        // Only calculate if at least 10 seconds have passed to avoid division issues
        if (elapsedSeconds >= 10) {
            killsPerHour = (killCount / (elapsedSeconds / 3600.0));
        }
        
        println("[COMPLETION] Kill #" + killCount + " complete! (" + String.format("%.1f", killsPerHour) + " kills/hour)");
        
        // Check if we should loot (every 3 kills)
        boolean shouldLoot = killCount % 3 == 0;
        
        if (shouldLoot) {
            println("[COMPLETION] Kill #" + killCount + " - Looting phase triggered");
            // Let the normal WAITING_FOR_LOOT -> LOOTING flow handle it (loot() will cast Invoke Death)
            return;
        }
        
        // Not a loot kill - set flag to cast Invoke Death and click obelisk in main loop
        // This avoids blocking the chat event handler
        shouldCastInvokeDeathAndClickObelisk = true;
    }
    
    private long handleBanking(LocalPlayer player) {
        println("[BANKING] >>> ENTERED <<<");
        
        // Step 1: Teleport to War's Retreat if not already done
        SceneObject magistersPortal = SceneObjectQuery.newQuery().name("Portal (The Magister)").results().nearest();
        if (magistersPortal == null) {
            println("[BANKING] Using War's Retreat Teleport...");
            ActionBar.useAbility("War's Retreat Teleport");
            hasTeleported = true;
            return random.nextLong(3000, 5000); // Wait for teleport
        }
        
        // Step 2: Look for Bank chest and load preset
        SceneObject bankChest = SceneObjectQuery.newQuery().name("Bank chest").results().nearest();
        if (bankChest != null && !hasLoadedPreset) {
            println("[BANKING] Bank chest found, loading last preset...");
            boolean interacted = bankChest.interact("Load Last Preset from");
            println("[BANKING] Load preset interaction result: " + interacted);
            if (interacted) {
                hasLoadedPreset = true;
                presetLoadedTime = System.currentTimeMillis();
                return random.nextLong(2000, 3000); // Wait for preset to load
            }
        } else if (!hasLoadedPreset) {
            println("[BANKING] No bank chest found nearby!");
            return random.nextLong(1000, 2000);
        }
        
        // Step 2.5: Check if keys exist after loading preset
        if (hasLoadedPreset && presetLoadedTime > 0) {
            long timeSincePresetLoad = System.currentTimeMillis() - presetLoadedTime;
            
            // Check if we have keys
            boolean hasKeys = Backpack.contains("Key to the Crossing");
            
            if (!hasKeys && timeSincePresetLoad > 5000) { // 5 seconds timeout
                println("[BANKING] ERROR: No keys found in inventory after loading preset!");
                println("[BANKING] Setting script to IDLE. Please check your preset or add keys to bank.");
                // Reset banking flags
                hasTeleported = false;
                hasLoadedPreset = false;
                presetLoadedTime = 0;
                setBotState(BotState.IDLE);
                return random.nextLong(1000, 2000);
            } else if (!hasKeys) {
                println("[BANKING] Waiting for keys to appear in inventory... (" + timeSincePresetLoad + "ms)");
                return random.nextLong(500, 1000);
            }
        }
        
        // Step 3: Return to Magister
        println("[BANKING] Preset loaded. Ready to return to Magister!");
        
        // Look for Portal (The Magister) and enter
        magistersPortal = SceneObjectQuery.newQuery().name("Portal (The Magister)").results().nearest();
        if (magistersPortal != null) {
            println("[BANKING] The Magister portal found, entering...");
            boolean interacted = magistersPortal.interact("Enter");
            println("[BANKING] Portal interaction result: " + interacted);
            if (interacted) {
                // Wait for teleport, then look for The First Gate to confirm we're back
                Execution.delay(random.nextLong(3000, 5000));
                SceneObject magistersEntrance = SceneObjectQuery.newQuery().name("The First Gate").results().nearest();
                if (magistersEntrance != null) {
                    println("[BANKING] The First Gate detected! Back at Magister. Entering...");
                    // Reset banking flags for next cycle
                    hasTeleported = false;
                    hasLoadedPreset = false;
                    presetLoadedTime = 0;
                    boolean entered = magistersEntrance.interact("Enter");
                    if (entered) {
                        println("[BANKING] Entered gate, waiting for arena to load...");
                        Execution.delay(random.nextLong(3000, 5000));
                        
                        // Wait for obelisk to appear (confirms we're in the arena)
                        boolean obeliskFound = Execution.delayUntil(5000, () -> {
                            EntityResultSet<SceneObject> obeliskCheck = SceneObjectQuery.newQuery()
                                    .name("Soul obelisk")
                                    .option("Touch")
                                    .results();
                            return !obeliskCheck.isEmpty();
                        });
                        
                        if (obeliskFound) {
                            println("[BANKING] Soul obelisk detected! Arena loaded. Switching to TOUCHING_OBELISK state.");
                            botState = BotState.TOUCHING_OBELISK;
                        } else {
                            println("[BANKING] Obelisk not found yet, waiting longer...");
                            return random.nextLong(2000, 3000);
                        }
                    }
                } else {
                    println("[BANKING] Portal used but no First Gate detected yet. Waiting...");
                }
            }
        } else {
            println("[BANKING] No Magister portal found nearby!");
        }
        
        return random.nextLong(600, 1200);
    }
   
}

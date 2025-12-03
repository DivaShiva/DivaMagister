package net.botwithus;


import net.botwithus.internal.scripts.ScriptDefinition;
import net.botwithus.rs3.events.impl.ServerTickedEvent;
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

import java.util.stream.Collectors;
import net.botwithus.rs3.game.scene.entities.object.SceneObject;
import net.botwithus.rs3.game.vars.VarManager;
import net.botwithus.rs3.script.Execution;
import net.botwithus.rs3.script.LoopingScript;
import net.botwithus.rs3.script.config.ScriptConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SkeletonScript extends LoopingScript {

    private BotState botState = BotState.IDLE;
    private boolean someBool = true;
    private boolean useVulnBombs = false;
    private boolean useDeathMark = false;
    private boolean useAdrenalineRenewal = false;
    private Random random = new Random();
    private boolean useSplitSoul = true; // Default enabled
    private int lastLoggedClientCycle = 0;
    private int serverTicks = 0;
    private int livingDeathActivatedServerTick = -1;
    private int lastAbilityServerTick = 0;
    private RotationManager rotation;

    enum BotState {
        IDLE,
        TOUCHING_OBELISK,
        HANDLING_DIALOG,
        FIGHTING_MAGISTER,
        WAITING_FOR_LOOT
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
            if (useDeathMark) {
                boolean deathMarkUsed = rotation.ensureDeathMarked();
                if (deathMarkUsed) {
                    lastAbilityServerTick = serverTicks;
                    println("Tick " + serverTicks + " - Using: Invoke Death");
                    return; // Skip normal rotation this tick
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
        }
    }

    private long handleTouchObelisk(LocalPlayer player) {
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
        
        println("[MAGISTER] Touching Soul obelisk");
        if (obelisk.interact("Touch")) {
            println("[MAGISTER] Successfully touched obelisk, waiting for dialog");
            botState = BotState.HANDLING_DIALOG;
            return random.nextLong(1200, 1800);
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
            // Reset Death Mark tracking for new target
            rotation.resetDeathMark();
            botState = BotState.FIGHTING_MAGISTER;
            return random.nextLong(600, 1000);
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
                return random.nextLong(800, 1200);
            } else {
                println("[MAGISTER] MiniMenu.interact failed, trying again");
                return random.nextLong(400, 600);
            }
        }
        
        // No dialog and no Magister - might be in transition
        println("[MAGISTER] No dialog detected and no Magister found, waiting...");
        return random.nextLong(600, 1000);
    }

    private long handleFighting(LocalPlayer player) {
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
        println("[MAGISTER] Waiting for loot to appear");
        
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
        
        // Loot appears quickly, go back to touching obelisk
        // You can add loot pickup logic here if needed
        println("[MAGISTER] Magister dead, restarting cycle");
        // Reset Death Mark for next kill
        rotation.resetDeathMark();
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
   
}

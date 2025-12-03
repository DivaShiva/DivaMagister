package net.botwithus;

import net.botwithus.rs3.game.Client;
import net.botwithus.rs3.game.actionbar.ActionBar;
import net.botwithus.rs3.game.hud.interfaces.Interfaces;
import net.botwithus.rs3.game.inventories.Backpack;
import net.botwithus.rs3.game.minimenu.MiniMenu;
import net.botwithus.rs3.game.minimenu.actions.ComponentAction;
import net.botwithus.rs3.game.queries.builders.items.InventoryItemQuery;
import net.botwithus.rs3.game.queries.results.EntityResultSet;
import net.botwithus.rs3.game.scene.entities.characters.npc.Npc;
import net.botwithus.rs3.game.scene.entities.characters.player.LocalPlayer;
import net.botwithus.rs3.game.vars.VarManager;
import net.botwithus.rs3.game.js5.types.vars.VarDomainType;
import net.botwithus.rs3.script.Execution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Manages combat rotations with support for abilities, inventory items, and custom actions.
 */
public class RotationManager {
    
    private final String name;
    private final boolean spend;
    private long lastExecutionTick;
    private boolean debug;
    private java.util.function.Consumer<String> logger;
    
    // Track bloat usage
    private long lastBloatTime = 0;
    
    // Track last ability used
    private String lastAbilityUsed = "None";
    
    // Track Conjure Undead Army usage (for 6 tick cooldown tracking)
    private int lastConjureArmyTick = -1;
    
    // Track if Command Ghost has been used for current summon
    private boolean commandGhostUsedThisSummon = false;
    
    // Track Essence of Finality usage (50 tick cooldown, initialized to -50 so it's ready immediately)
    private int lastEssenceOfFinalityTick = -50;
    
    // Track Weapon Special Attack usage (100 tick cooldown, initialized to -100 so it's ready immediately)
    private int lastWeaponSpecialTick = -100;
    
    // Current server tick (updated externally)
    private int serverTick = 0;
    
    // Manual cooldown tracking - stores server tick when ability was last used
    private java.util.Map<String, Integer> lastUsedTick = new java.util.HashMap<>();
    
    // Ability cooldowns in server ticks (1 tick = 0.6 seconds)
    private static final java.util.Map<String, Integer> ABILITY_COOLDOWNS = new java.util.HashMap<String, Integer>() {{
        put("Death Skulls", 100);  // 60 seconds
        put("Split Soul", 100);    // 60 seconds
        put("Living Death", 150);  // 90 seconds
        put("Volley of Souls", 0); // No cooldown (basic ability)
        put("Finger of Death", 0); // No cooldown (basic ability)
        put("Bloat", 0);          // 25 seconds (approximate)
        put("Weapon Special Attack", 100); // 60 seconds
        put("Essence of Finality", 50);    // 30 seconds
        put("Conjure Undead Army", 125);   // 75 seconds
        put("Life Transfer", 75);          // 45 seconds
        put("Conjure Skeleton Warrior", 0); // No cooldown
        put("Command Skeleton Warrior", 26); // 15 seconds
        put("Conjure Vengeful Ghost", 0);   // No cooldown
        put("Command Vengeful Ghost", 0);   // No cooldown (tracked by flag instead)
        put("Touch of Death", 24);          // 14.4 seconds
        put("Soul Sap", 9);                 // 5.4 seconds
        put("Invoke Death", 0);             // No cooldown (applies Death Mark debuff)
        put("Basic<nbsp>Attack", 0);        // No cooldown
    }};
    
    // Cache ability slot positions to reduce ActionBar queries
    private java.util.Map<String, Integer> slotCache = new java.util.HashMap<>();
    private long lastSlotCacheUpdate = 0;
    private boolean slotCacheInitialized = false;
    
    public RotationManager(String name, boolean spend) {
        this.name = name;
        this.spend = spend;
        this.lastExecutionTick = 0;
        this.debug = false;
        this.logger = System.out::println; // Default to System.out
    }
    
    public void setDebug(boolean debug) {
        this.debug = debug;
    }
    
    public void setLogger(java.util.function.Consumer<String> logger) {
        this.logger = logger;
    }
    
    public void setServerTick(int serverTick) {
        this.serverTick = serverTick;
    }
    
    private void debugLog(String message) {
        if (debug && logger != null) {
            logger.accept("[ROTATION]: " + message);
        }
    }
    
    /**
     * Execute the rotation (improvise necromancy ability)
     * @return true if an ability was executed, false otherwise
     */
    public boolean execute() {
        // Check if we need to drink Adrenaline Renewal from previous tick
        if (drinkAdrenNextTick) {
            drinkAdrenNextTick = false;
            drinkAdrenalineRenewal();
        }
        
        if (!canTrigger()) {
            return false;
        }
        
        debugLog("--# 0 -------------------------------------------");
        debugLog("# Improvise");
        debugLog("Tick: " + Client.getClientCycle());
        
        try {
            // Query all varbits once here to reduce query frequency
            LocalPlayer player = Client.getLocalPlayer();
            if (player == null) {
                debugLog("Player is null");
                return false;
            }
            
            int necrosisStacks = 0;
            int soulStacks = 0;
            int livingDeathTick = -1;
            boolean bloated = false;
            int armyConjureStatus = 0;
            
            try {
                necrosisStacks = VarManager.getVarValue(VarDomainType.PLAYER, 10986);
                soulStacks = VarManager.getVarValue(VarDomainType.PLAYER, 11035);
                livingDeathTick = VarManager.getVarValue(VarDomainType.PLAYER, 11059);
                bloated = VarManager.getVarbitValue(53245) == 1;
                armyConjureStatus = VarManager.getVarValue(VarDomainType.PLAYER, 11018);
            } catch (Exception e) {
                debugLog("[ERROR] Exception querying varbits: " + e.getMessage());
                // Continue with default values
            }
            
            String ability = improviseNecromancy(spend, player, necrosisStacks, soulStacks, livingDeathTick, bloated, armyConjureStatus);
            debugLog("= Designated improvise ability: " + ability);
            lastAbilityUsed = ability;
            boolean success = useAbility(ability);
            debugLog(success ? "+ Ability cast was successful" : "- Ability cast was unsuccessful");
            
            if (success) {
                // Record ability use for manual cooldown tracking
                recordAbilityUse(ability);
                updateTimer();
            }
            
            return success;
        } catch (Exception e) {
            debugLog("[ERROR] Exception in improvise: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    private boolean useAbility(String abilityName) {
        try {
            // Basic Attack is always available, don't check cache
            if (abilityName.equals("Basic<nbsp>Attack")) {
                return ActionBar.useAbility(abilityName);
            }
            
            // Check if ability is in cache (means it exists on action bar)
            if (slotCacheInitialized && !slotCache.containsKey(abilityName)) {
                debugLog("Ability (" + abilityName + ") not in cache - skipping");
                return true; // Return true to continue rotation
            }
            
            // Use ability by name
            return ActionBar.useAbility(abilityName);
        } catch (Exception e) {
            debugLog("[ERROR] Exception in useAbility: " + e.getMessage());
            return false;
        }
    }
    
    private boolean useInventory(String itemName) {
        // Try action bar first
        if (ActionBar.containsItem(itemName)) {
            return ActionBar.useItem(itemName, "Drink") || 
                   ActionBar.useItem(itemName, "Eat") || 
                   ActionBar.useItem(itemName, "Use");
        }
        
        // Fallback to backpack using Backpack.interact
        return Backpack.interact(itemName, "Drink") || 
               Backpack.interact(itemName, "Eat") || 
               Backpack.interact(itemName, "Use");
    }
    
    private boolean canTrigger() {
        long currentTick = Client.getClientCycle();
        return (currentTick - lastExecutionTick) >= 3; // 3 ticks between abilities
    }
    
    private void updateTimer() {
        lastExecutionTick = Client.getClientCycle();
        debugLog("= Timer updated with wait: 3 ticks");
        debugLog("= Current cycle: " + lastExecutionTick);
    }
    
    /**
     * Improvise necromancy ability based on current combat state
     */
    private String improviseNecromancy(boolean spend, LocalPlayer player, int necrosisStacks, int soulStacks, int livingDeathTick, boolean bloated, int armyConjureStatus) {
        String ability = "Basic<nbsp>Attack"; // Default ability
        
        // Reset command ghost flag if summons are no longer active
        if (armyConjureStatus == 0) {
            commandGhostUsedThisSummon = false;
        }
        
        int targetHealth = 0;
        int adrenaline = 0;
        int health = 0;
        
        try {
            Npc target = (Npc) player.getTarget();
            if (target != null) {
                targetHealth = target.getCurrentHealth();
            }
            
            // Adrenaline is stored as 0-1000, divide by 10 to get percentage
            int adrenalineRaw = player.getAdrenaline();
            adrenaline = adrenalineRaw / 10;
            
            // Health (raw value for comparison with 8000/9000 thresholds)
            health = player.getCurrentHealth();
        } catch (Exception e) {
            debugLog("[ERROR] Exception getting player/target stats: " + e.getMessage());
            // Continue with default values
        }
        
        // Check Living Death status (-1 if inactive, tick number if active)
        boolean livingDeath = livingDeathTick != -1;
        
        // Get cooldowns for conditional checks
        int deathSkullsCooldown = getAbilityCooldown("Death Skulls");
        int livingDeathCooldown = getAbilityCooldown("Living Death");
        
        debugLog("[IMPROV]: = Target Health:    " + targetHealth);
        debugLog("[IMPROV]: = Adrenaline:       " + adrenaline);
        debugLog("[IMPROV]: = Health:           " + health);
        debugLog("[IMPROV]: = Necrosis stacks:  " + necrosisStacks);
        debugLog("[IMPROV]: = Soul stacks:      " + soulStacks);
        debugLog("[IMPROV]: = Living Death:     " + livingDeath);
        debugLog("[IMPROV]: = Death Skulls CD:  " + deathSkullsCooldown);
        debugLog("[IMPROV]: = Living Death CD:  " + livingDeathCooldown);
        debugLog("[IMPROV]: = Server Tick:      " + serverTick);
        debugLog("[IMPROV]: = Weapon Spec CD:   " + (serverTick - lastWeaponSpecialTick) + "/100");
        debugLog("[IMPROV]: = EOF CD:           " + (serverTick - lastEssenceOfFinalityTick) + "/50");
        debugLog("[IMPROV]: = Summons Active:   " + (armyConjureStatus == 1) + " (status: " + armyConjureStatus + ")");
        

        
        if (livingDeath) {
            debugLog("[IMPROV]: Using Living Death rotation");
            // Living Death rotation
            if (isAbilityReady("Death Skulls") && adrenaline >= 60) {
                ability = "Death Skulls";
                debugLog("[IMPROV]: Living Death - Death Skulls");
            } else if (isAbilityReady("Touch of Death") && adrenaline < 60) {
                ability = "Touch of Death";
                debugLog("[IMPROV]: Living Death - Touch of Death (low adrenaline)");
            } else if ((deathSkullsCooldown > 8 || adrenaline > 60) && necrosisStacks >= 6) {
                ability = "Finger of Death";
                debugLog("[IMPROV]: Living Death - Finger of Death");
            } else if (isAbilityReady("Touch of Death")) {
                ability = "Touch of Death";
                debugLog("[IMPROV]: Living Death - Touch of Death");
            } else if ((deathSkullsCooldown >= 8 || adrenaline > 60) && isAbilityReady("Command Skeleton Warrior")) {
                ability = "Command Skeleton Warrior";
                debugLog("[IMPROV]: Living Death - Command Skeleton Warrior");
            } else {
                // No ability to use, just wait
                
                debugLog("[IMPROV]: Living Death - No ability ready, waiting");
                return ability;
            }
        } else {
            debugLog("[IMPROV]: Using Normal rotation");
            
            // Check each ability individually with error handling
            try {
                if (isAbilityReady("Death Skulls") && adrenaline >= 60) {
                    ability = "Death Skulls";
                    debugLog("[IMPROV]: Normal - Death Skulls");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Death Skulls check: " + e.getMessage()); }
            
            try {
                if (useSplitSoul && isAbilityReady("Split Soul")) {
                    ability = "Split Soul";
                    debugLog("[IMPROV]: Normal - Split Soul");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Split Soul check: " + e.getMessage()); }
            
            try {
                if (spend && targetHealth > 20000 && isAbilityReady("Living Death") && adrenaline >= 100) {
                    ability = "Living Death";
                    debugLog("[IMPROV]: Normal - Living Death");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Living Death check: " + e.getMessage()); }
            
            try {
                if (soulStacks >= 5 && isAbilityReady("Volley of Souls")) {
                    ability = "Volley of Souls";
                    debugLog("[IMPROV]: Normal - Volley of Souls");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Volley of Souls check: " + e.getMessage()); }
            
            try {
                if (necrosisStacks >= 6 && isAbilityReady("Finger of Death") && 
                    (adrenaline != 100 || livingDeathCooldown > 10 || !spend)) {
                    ability = "Finger of Death";               debugLog("[IMPROV]: Normal - Finger of Death");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Finger of Death check: " + e.getMessage()); }
            
            try {
                if (targetHealth > 20000 && isAbilityReady("Bloat") && !bloated && adrenaline > 20 && 
                    (adrenaline != 100 || livingDeathCooldown > 10 || !spend) &&
                    (System.currentTimeMillis() - lastBloatTime >= 20000)) {
                    ability = "Bloat";
                    lastBloatTime = System.currentTimeMillis();
                    debugLog("[IMPROV]: Normal - Bloat");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Bloat check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Weapon Special Attack") && adrenaline >= 27 &&
                    (adrenaline != 100 || livingDeathCooldown < 10) && necrosisStacks >= 4 &&
                    (serverTick - lastWeaponSpecialTick >= 100)) {
                    ability = "Weapon Special Attack";
                    lastWeaponSpecialTick = serverTick;
                    debugLog("[IMPROV]: Normal - Special Attack (used at tick " + serverTick + ")");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Weapon Special check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Essence of Finality") && adrenaline >= 23 &&
                    (adrenaline != 100 || livingDeathCooldown < 10) && necrosisStacks >= 4 &&
                    (serverTick - lastEssenceOfFinalityTick >= 50)) {
                    ability = "Essence of Finality";
                    lastEssenceOfFinalityTick = serverTick;
                    debugLog("[IMPROV]: Normal - Essence of Finality (used at tick " + serverTick + ")");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] EOF check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Conjure Undead Army", armyConjureStatus)) {
                    ability = "Conjure Undead Army";
                    lastConjureArmyTick = serverTick;
                    debugLog("[IMPROV]: Normal - Conjure Army");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Conjure Army check: " + e.getMessage()); }
            
            try {
                // Don't use Life Transfer if Conjure Army is almost ready (within 5 seconds / 8 ticks)
                int armyCooldown = getAbilityCooldown("Conjure Undead Army");
                boolean armyAlmostReady = armyCooldown > 0 && armyCooldown <= 8;
                
                if (isAbilityReady("Life Transfer") && health > 9000 && !armyAlmostReady) {
                    ability = "Life Transfer";
                    debugLog("[IMPROV]: Normal - Life Transfer");
                    return ability;
                } else if (isAbilityReady("Life Transfer") && health > 9000 && armyAlmostReady) {
                    debugLog("[IMPROV]: Skipping Life Transfer - Conjure Army ready in " + armyCooldown + " ticks");
                }
            } catch (Exception e) { debugLog("[ERROR] Life Transfer check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Command Skeleton Warrior") && armyConjureStatus == 1) {
                    ability = "Command Skeleton Warrior";
                    debugLog("[IMPROV]: Normal - Command Skeleton");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Command Skeleton check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Command Vengeful Ghost") && armyConjureStatus == 1 && !commandGhostUsedThisSummon) {
                    ability = "Command Vengeful Ghost";
                    commandGhostUsedThisSummon = true;
                    debugLog("[IMPROV]: Normal - Command Ghost");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Command Ghost check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Touch of Death")) {
                    ability = "Touch of Death";
                    debugLog("[IMPROV]: Normal - Touch of Death");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Touch of Death check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Soul Sap")) {
                    ability = "Soul Sap";
                    debugLog("[IMPROV]: Normal - Soul Sap");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Soul Sap check: " + e.getMessage()); }
            
            try {
                // Don't use Life Transfer if Conjure Army is almost ready (within 5 seconds / 8 ticks)
                int armyCooldown = getAbilityCooldown("Conjure Undead Army");
                boolean armyAlmostReady = armyCooldown > 0 && armyCooldown <= 8;
                
                if (isAbilityReady("Life Transfer") && health > 8000 && !armyAlmostReady) {
                    ability = "Life Transfer";
                    debugLog("[IMPROV]: Normal - Life Transfer (secondary)");
                    return ability;
                } else if (isAbilityReady("Life Transfer") && health > 8000 && armyAlmostReady) {
                    debugLog("[IMPROV]: Skipping Life Transfer (secondary) - Conjure Army ready in " + armyCooldown + " ticks");
                }
            } catch (Exception e) { debugLog("[ERROR] Life Transfer secondary check: " + e.getMessage()); }
            
            ability = "Basic<nbsp>Attack";
            debugLog("[IMPROV]: Normal - Basic Attack");
        }
        
        return ability;
    }
    
    /**
     * Scan action bar and cache ability slot positions
     * Can be called manually from GUI or automatically
     */
    private void updateSlotCache() {
        long currentTime = System.currentTimeMillis();
        // Only update slot cache every 30 seconds (abilities rarely move)
        if (slotCacheInitialized && currentTime - lastSlotCacheUpdate < 30000) {
            return;
        }
        
        performSlotScan();
    }
    
    /**
     * Manually trigger action bar scan (called from GUI button)
     */
    public void scanActionBar() {
        debugLog("[MANUAL SCAN] Starting action bar scan...");
        performSlotScan();
        debugLog("[MANUAL SCAN] Scan complete - found " + slotCache.size() + " abilities");
    }
    
    /**
     * Perform the actual slot scanning
     */
    private void performSlotScan() {
        debugLog("[CACHE] Scanning action bar for ability slots...");
        slotCache.clear();
        lastSlotCacheUpdate = System.currentTimeMillis();
        
        String[] abilities = {
            "Death Skulls", "Split Soul", "Living Death", "Volley of Souls",
            "Finger of Death", "Bloat", "Weapon Special Attack", "Essence of Finality",
            "Conjure Undead Army", "Life Transfer", "Touch of Death", "Soul Sap",
            "Invoke Death", "Basic<nbsp>Attack"
        };
        
        // Pairs of abilities that share the same slot (Conjure transforms to Command)
        String[][] transformPairs = {
            {"Conjure Skeleton Warrior", "Command Skeleton Warrior"},
            {"Conjure Vengeful Ghost", "Command Vengeful Ghost"}
        };
        
        // Scan the first 5 action bars (bars 1-5, slots 1-14)
        for (String ability : abilities) {
            try {
                boolean found = false;
                for (int bar = 1; bar <= 5 && !found; bar++) {
                    for (int slot = 1; slot <= 14 && !found; slot++) {
                        try {
                            net.botwithus.rs3.game.js5.types.StructType struct = ActionBar.getAbilityIn(bar, slot);
                            if (struct != null) {
                                String slotAbilityName = ActionBar.getActionName(struct.getParams());
                                if (slotAbilityName != null && slotAbilityName.equalsIgnoreCase(ability)) {
                                    int combinedSlot = (bar - 1) * 14 + (slot - 1);
                                    slotCache.put(ability, combinedSlot);
                                    debugLog("[CACHE] Found " + ability + " at bar " + bar + " slot " + slot);
                                    found = true;
                                }
                            }
                        } catch (Exception e) {
                            // Slot might be empty, continue
                        }
                    }
                }
            } catch (Exception e) {
                debugLog("[ERROR] Exception scanning for " + ability + ": " + e.getMessage());
            }
        }
        
        // Scan for transform pairs - if we find one, cache both names
        for (String[] pair : transformPairs) {
            try {
                boolean found = false;
                for (int bar = 1; bar <= 5 && !found; bar++) {
                    for (int slot = 1; slot <= 14 && !found; slot++) {
                        try {
                            net.botwithus.rs3.game.js5.types.StructType struct = ActionBar.getAbilityIn(bar, slot);
                            if (struct != null) {
                                String slotAbilityName = ActionBar.getActionName(struct.getParams());
                                if (slotAbilityName != null) {
                                    // Check if this slot contains either version
                                    for (String abilityName : pair) {
                                        if (slotAbilityName.equalsIgnoreCase(abilityName)) {
                                            int combinedSlot = (bar - 1) * 14 + (slot - 1);
                                            // Cache BOTH names for this slot
                                            slotCache.put(pair[0], combinedSlot);
                                            slotCache.put(pair[1], combinedSlot);
                                            debugLog("[CACHE] Found " + slotAbilityName + " at bar " + bar + " slot " + slot);
                                            debugLog("[CACHE] Cached both " + pair[0] + " and " + pair[1]);
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Slot might be empty, continue
                        }
                    }
                }
            } catch (Exception e) {
                debugLog("[ERROR] Exception scanning for transform pair: " + e.getMessage());
            }
        }
        
        // Note: Adrenaline Renewal is searched in backpack when needed, no need to cache
        
        slotCacheInitialized = true;
        debugLog("[CACHE] Slot cache initialized with " + slotCache.size() + " abilities");
    }
    
    /**
     * Get the number of cached abilities
     */
    public int getCachedAbilityCount() {
        return slotCache.size();
    }
    
    /**
     * Print all cached ability slots
     */
    public void printCachedSlots() {
        if (slotCache.isEmpty()) {
            debugLog("[CACHE] No abilities cached. Click 'Scan Action Bar' first.");
            return;
        }
        
        debugLog("[CACHE] === Cached Ability Slots ===");
        for (java.util.Map.Entry<String, Integer> entry : slotCache.entrySet()) {
            debugLog("[CACHE] " + entry.getKey() + " -> Slot " + entry.getValue());
        }
        debugLog("[CACHE] === Total: " + slotCache.size() + " abilities ===");
    }
    
    /**
     * Update cooldown cache using cached slot positions
     */
    public void reset() {
        this.lastExecutionTick = 0;
        debugLog("Rotation reset");
    }
    
    /**
     * Reset Death Mark tracking (call when starting a new target)
     */
    public void resetDeathMark() {
        this.invokeDeathBuffActive = false;
        debugLog("[DEATH MARK] Reset - ready to apply to new target");
    }
    
    public String getLastAbilityUsed() {
        return lastAbilityUsed;
    }
    
    public void setUseAdrenalineRenewal(boolean useAdrenalineRenewal) {
        this.useAdrenalineRenewal = useAdrenalineRenewal;
    }
    
    public boolean isUseAdrenalineRenewal() {
        return useAdrenalineRenewal;
    }
    
    public void setUseSplitSoul(boolean useSplitSoul) {
        this.useSplitSoul = useSplitSoul;
    }
    
    public boolean isUseSplitSoul() {
        return useSplitSoul;
    }
    
    /**
     * Drink Adrenaline Renewal potion
     */
    private void drinkAdrenalineRenewal() {
        try {
            debugLog("[ADREN RENEWAL] Searching for Adrenaline Renewal");
            
            // Search backpack for any Adrenaline Renewal potion
            net.botwithus.rs3.game.Item pot = InventoryItemQuery.newQuery(93).results().stream()
                    .filter(i -> i.getName() != null && i.getName().toLowerCase().contains("adrenaline renewal"))
                    .findFirst().orElse(null);
            
            if (pot != null) {
                debugLog("[ADREN RENEWAL] Drinking " + pot.getName());
                if (Backpack.interact(pot.getName(), "Drink")) {
                    debugLog("[ADREN RENEWAL] Successfully drank " + pot.getName());
                } else {
                    debugLog("[ADREN RENEWAL] Failed to drink " + pot.getName());
                }
            } else {
                debugLog("[ADREN RENEWAL] No Adrenaline Renewal pot found in backpack");
            }
        } catch (Exception e) {
            debugLog("[ERROR] Exception in drinkAdrenalineRenewal: " + e.getMessage());
        }
    }
    
    /**
     * Ensure target has Death Mark - uses Invoke Death ability if not
     * @return true if ability was used this tick (consumes GCD), false otherwise
     */
    public boolean ensureDeathMarked() {
        try {
            // Check Death Mark varbit (53247)
            boolean deathMarked = VarManager.getVarbitValue(53247) == 1;
            
            if (deathMarked) {
                invokeDeathBuffActive = false; // Death Mark applied, buff consumed
                return false; // Already applied, don't consume GCD
            }
            
            // If Invoke Death buff is active, wait for next attack to apply it
            if (invokeDeathBuffActive) {
                debugLog("[DEATH MARK] Invoke Death buff active, waiting for next attack to apply");
                return false; // Don't recast, let normal rotation continue
            }
            
            // Check if Invoke Death is ready
            if (!isAbilityReady("Invoke Death")) {
                debugLog("[DEATH MARK] Invoke Death not ready");
                return false;
            }
            
            // Target not death marked, use Invoke Death ability
            debugLog("[DEATH MARK] Target not death marked, using Invoke Death");
            boolean success = useAbility("Invoke Death");
            
            if (success) {
                recordAbilityUse("Invoke Death");
                invokeDeathBuffActive = true; // Buff is now active
                debugLog("[DEATH MARK] Successfully used Invoke Death - buff active");
                return true; // Ability used, consumes GCD
            }
            
            debugLog("[DEATH MARK] Failed to use Invoke Death");
            return false;
            
        } catch (Exception e) {
            debugLog("[ERROR] Exception in ensureDeathMarked: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Ensure target is vulnerabled - uses Vulnerability Bomb if not
     * @return true if target is vulnerabled or bomb was used successfully
     */
    public boolean ensureVulned() {
        try {
            // Check vulnerability varbit (1939)
            boolean vulned = VarManager.getVarbitValue(1939) == 1;
            
            if (vulned) {
                return true;
            }
            
            // Check if we recently threw a bomb (wait 5 ticks for it to land + apply)
            int ticksSinceLastBomb = serverTick - lastVulnBombTick;
            if (ticksSinceLastBomb < 5) {
                debugLog("[VULN] Waiting for bomb to land (" + ticksSinceLastBomb + "/5 ticks)");
                return false;
            }
            
            // Target not vulnerabled and enough time has passed, try to use Vulnerability Bomb
            debugLog("[VULN] Target not vulnerabled, attempting to use Vulnerability Bomb");
            
            boolean success = false;
            
            // Try action bar first
            if (ActionBar.containsItem("Vulnerability bomb")) {
                success = ActionBar.useItem("Vulnerability bomb", "Throw");
                if (success) {
                    debugLog("[VULN] Successfully used Vulnerability Bomb from action bar");
                }
            }
            
            // Fallback to backpack
            if (!success && Backpack.contains("Vulnerability bomb")) {
                success = Backpack.interact("Vulnerability bomb", "Throw");
                if (success) {
                    debugLog("[VULN] Successfully used Vulnerability Bomb from backpack");
                }
            }
            
            if (success) {
                lastVulnBombTick = serverTick;
                return true;
            }
            
            debugLog("[VULN] Vulnerability Bomb not found or failed to use");
            return false;
            
        } catch (Exception e) {
            debugLog("[ERROR] Exception in ensureVulned: " + e.getMessage());
            return false;
        }
    }
    
    // Track if Death Skulls was last used during Living Death
    private boolean deathSkullsUsedDuringLD = false;
    
    // Track last vuln bomb throw to prevent spam (2.4 seconds = 4 ticks to land)
    private int lastVulnBombTick = -10;
    
    // Track if Invoke Death buff is active (waiting for next attack to apply Death Mark)
    private boolean invokeDeathBuffActive = false;
    
    // Setting: Use Adrenaline Renewal with Living Death
    private boolean useAdrenalineRenewal = false;
    
    // Setting: Use Split Soul
    private boolean useSplitSoul = true; // Default enabled
    
    // Flag to drink Adrenaline Renewal on next tick (after Living Death fires)
    private boolean drinkAdrenNextTick = false;
    
    /**
     * Get the remaining cooldown of an ability in ticks based on manual tracking
     * @return remaining cooldown in ticks, or 0 if ready
     */
    private int getAbilityCooldown(String abilityName) {
        Integer maxCooldown = ABILITY_COOLDOWNS.get(abilityName);
        if (maxCooldown == null) {
            return 0; // Unknown ability
        }
        
        // Special case: Command Ghost has 6-tick cooldown after Conjure Army
        if (abilityName.equals("Command Vengeful Ghost") && maxCooldown == 0) {
            Integer lastUsed = lastUsedTick.get(abilityName);
            Integer armyUsed = lastUsedTick.get("Conjure Undead Army");
            if (lastUsed != null && armyUsed != null && lastUsed.equals(armyUsed)) {
                // Command Ghost was set on cooldown by Conjure Army
                int ticksSinceUse = serverTick - lastUsed;
                return Math.max(0, 6 - ticksSinceUse);
            }
            return 0; // No cooldown normally
        }
        
        if (maxCooldown == 0) {
            return 0; // No cooldown
        }
        
        Integer lastUsed = lastUsedTick.get(abilityName);
        if (lastUsed == null) {
            return 0; // Never used, so it's ready
        }
        
        // Special case: Death Skulls has reduced cooldown during Living Death
        if (abilityName.equals("Death Skulls")) {
            Integer livingDeathUsed = lastUsedTick.get("Living Death");
            if (livingDeathUsed != null) {
                int ticksSinceLivingDeath = serverTick - livingDeathUsed;
                // Living Death lasts 30 seconds (50 ticks)
                boolean livingDeathActive = ticksSinceLivingDeath < 50;
                
                if (livingDeathActive || deathSkullsUsedDuringLD) {
                    // During Living Death OR if last use was during LD: 12 second cooldown
                    maxCooldown = 20;
                }
            }
        }
        
        int ticksSinceUse = serverTick - lastUsed;
        int remaining = maxCooldown - ticksSinceUse;
        
        return Math.max(0, remaining);
    }
    
    /**
     * Check if an ability is ready based on manual cooldown tracking
     */
    private boolean isAbilityReady(String abilityName) {
        // Check if ability exists in cache
        if (slotCacheInitialized && !slotCache.containsKey(abilityName)) {
            return false;
        }
        
        // Check manual cooldown
        return getAbilityCooldown(abilityName) <= 1;
    }
    
    /**
     * Check if an ability is ready, with armyConjureStatus for Conjure Undead Army
     */
    private boolean isAbilityReady(String abilityName, int armyConjureStatus) {
        // Special case: Conjure Undead Army is ready when armyConjureStatus == 0
        if (abilityName.equals("Conjure Undead Army")) {
            return armyConjureStatus == 0 && (serverTick - lastConjureArmyTick >= 6);
        }
        
        // For all other abilities, use normal cooldown tracking
        return isAbilityReady(abilityName);
    }
    
    /**
     * Record that an ability was used at the current server tick
     */
    private void recordAbilityUse(String abilityName) {
        lastUsedTick.put(abilityName, serverTick);
        debugLog("[COOLDOWN] Recorded " + abilityName + " used at tick " + serverTick);
        
        // Special case: Living Death resets Death Skulls and Touch of Death cooldowns
        if (abilityName.equals("Living Death")) {
            lastUsedTick.remove("Death Skulls");
            lastUsedTick.remove("Touch of Death");
            deathSkullsUsedDuringLD = false; // Reset the flag
            debugLog("[COOLDOWN] Living Death reset Death Skulls and Touch of Death cooldowns");
            
            // Set flag to drink Adrenaline Renewal on next tick
            if (useAdrenalineRenewal) {
                drinkAdrenNextTick = true;
                debugLog("[ADREN RENEWAL] Will drink on next tick");
            }
        }
        
        // Special case: Track if Death Skulls is used during Living Death
        if (abilityName.equals("Death Skulls")) {
            Integer livingDeathUsed = lastUsedTick.get("Living Death");
            if (livingDeathUsed != null) {
                int ticksSinceLivingDeath = serverTick - livingDeathUsed;
                // Living Death lasts 30 seconds (50 ticks)
                if (ticksSinceLivingDeath < 50) {
                    deathSkullsUsedDuringLD = true;
                    debugLog("[COOLDOWN] Death Skulls used during Living Death - will keep 12s cooldown");
                } else {
                    deathSkullsUsedDuringLD = false;
                    debugLog("[COOLDOWN] Death Skulls used outside Living Death - back to 60s cooldown");
                }
            } else {
                deathSkullsUsedDuringLD = false;
            }
        }
        
        // Special case: Conjure Undead Army resets Command Ghost usage flag and puts Command abilities on 3.6s cooldown
        if (abilityName.equals("Conjure Undead Army")) {
            commandGhostUsedThisSummon = false;
            // Command abilities get 3.6 second (6 tick) cooldown when army is conjured
            // Set both to current tick - they'll have 6 tick delay via special logic
            lastUsedTick.put("Command Skeleton Warrior", serverTick - 19); // Will be ready in 6 ticks (25 - 19 = 6)
            lastUsedTick.put("Command Vengeful Ghost", serverTick); // Will be ready in 6 ticks (special case in getAbilityCooldown)
            debugLog("[COOLDOWN] Conjure Army reset Command Ghost flag and set Command abilities on 6 tick cooldown");
        }
        
        // Special case: Life Transfer extends summon duration by 21 seconds (35 ticks)
        // This delays when Conjure Undead Army can be used again
        if (abilityName.equals("Life Transfer")) {
            Integer armyLastUsed = lastUsedTick.get("Conjure Undead Army");
            if (armyLastUsed != null) {
                // Push back the "last used" time by 35 ticks to simulate extended duration
                lastUsedTick.put("Conjure Undead Army", armyLastUsed - 35);
                debugLog("[COOLDOWN] Life Transfer extended Conjure Army cooldown by 35 ticks");
            }
        }
    }
}

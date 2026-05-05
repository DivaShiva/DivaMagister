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

import static net.botwithus.rs3.game.Client.getClientCycle;

/**
 * Manages combat rotations with support for abilities, inventory items, and custom actions.
 * 
 * NEW FEATURES:
 * - Previous ability tracking: Keeps track of the ability used before the current one
 * - Ability sequence validation: Verifies abilities were actually used by checking cooldowns
 * - Enhanced debugging: Provides detailed sequence information for troubleshooting
 * 
 * USAGE EXAMPLES:
 * - rotation.getPreviousAbilityUsed() - Get the ability used before current one
 * - rotation.isPreviousAbilityOnCooldown() - Check if previous ability is on cooldown (confirms use)
 * - rotation.getPreviousAbilityCooldown() - Get exact cooldown remaining on previous ability
 * - rotation.getRotationSequenceInfo() - Get formatted sequence info for logging
 */
public class RotationManager {
    
    private final String name;
    private boolean spend;
    private long lastExecutionTick;
    private boolean debug;
    private java.util.function.Consumer<String> logger;
    
    // Track bloat usage
    private long lastBloatTime = 0;
    
    // Track last ability used
    private String lastAbilityUsed = "None";
    
    // Track previous ability used (for sequence validation)
    private String previousAbilityUsed = "None";
    
    // Track Conjure Undead Army usage (for 6 tick cooldown tracking)
    private int lastConjureArmyTick = -1;
    
    // Track Undead Army duration (98 ticks base, 133 ticks with Life Transfer)
    private int undeadArmyExpiresTick = -1; // Server tick when army expires
    private static final int UNDEAD_ARMY_BASE_DURATION = 98; // 58.8 seconds
    private static final int UNDEAD_ARMY_LIFE_TRANSFER_EXTENSION = 35; // 21 seconds
    
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
        put("Command Putrid Zombie", 26);    // 15 seconds (same as Command Skeleton Warrior)
        put("Touch of Death", 25);          // 15 seconds (14.4s + 1 tick buffer)
        put("Soul Sap", 9);                 // 5.4 seconds
        put("Invoke Death", 0);             // No cooldown (applies Death Mark debuff)
        put("Basic<nbsp>Attack", 0);        // No cooldown
        put("Threads of Fate", 75);         // 45 seconds
        put("Devotion", 102);               // 61 seconds
    }};
    
    // Cache ability slot positions to reduce ActionBar queries
    private java.util.Map<String, Integer> slotCache = new java.util.HashMap<>();
    private long lastSlotCacheUpdate = 0;
    private boolean slotCacheInitialized = false;
    
    // Settings
    private boolean useLivingDeath = true; // Use Living Death ultimate
    private boolean useAdrenalineRenewal = false; // Drink Adrenaline Renewal with Living Death
    private boolean useEssenceOfFinality = true; // Use Essence of Finality special attack
    private boolean useWeaponSpecial = true; // Use Weapon Special Attack
    private boolean useDeathSkulls = true; // Use Death Skulls ability
    private boolean useVolleyOfSouls = true; // Use Volley of Souls ability
    private boolean basicsOnly = false; // When true, only use Soul Sap, Touch of Death, Basic Attack
    private boolean useLifeTransfer = true; // Use Life Transfer ability
    private String queuedAbility = null; // Ability queued to be used next, overrides normal rotation
    private int threadsOfFateCharges = 0; // Remaining AoE charges from Threads of Fate (3 after use, decrements each ability)
    private int lastThreadsChargeTick = -1; // Tick when last Threads charge was consumed (for expiry)
    private int nearbyNpcCount = 0; // Number of living NPCs within 7 tiles (set by SkeletonScript each tick)
    private boolean useThreadsOfFate = true; // Whether to auto-use Threads of Fate when conditions met
    
    // Callback for when Life Transfer is used
    private Runnable onLifeTransferUsed = null;
    
    // Callback for using Basic Attack (ActionBar.useAbility no longer works for it)
    private java.util.function.Supplier<Boolean> basicAttackHandler = null;
    
    public RotationManager(String name, boolean spend) {
        this.name = name;
        this.spend = spend;
        this.lastExecutionTick = 0;
        this.debug = false;
        this.logger = System.out::println; // Default to System.out
        this.useLivingDeath = spend; // Initialize from constructor parameter
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
    
    /**
     * Set whether to spend adrenaline on abilities like Living Death, Death Skulls, etc.
     * When false, only uses basic abilities (Soul Sap, Touch of Death, Commands, Conjures)
     * @param spend true to use adrenaline-spending abilities, false to conserve
     */
    public void setSpendAdrenaline(boolean spend) {
        this.spend = spend;
    }
    
    /**
     * Get whether currently spending adrenaline
     */
    public boolean isSpendingAdrenaline() {
        return this.spend;
    }
    
    /**
     * Queue an ability to be used next, overriding the normal rotation
     * The ability will be used on the next execute() call and then cleared
     * @param abilityName the name of the ability to queue
     */
    public void queueAbility(String abilityName) {
        this.queuedAbility = abilityName;
        debugLog("Queued ability: " + abilityName);
    }
    
    /**
     * Clear the queued ability without using it
     */
    public void clearQueuedAbility() {
        this.queuedAbility = null;
        debugLog("Cleared queued ability");
    }
    
    /**
     * Check if there's an ability queued
     * @return true if an ability is queued
     */
    public boolean hasQueuedAbility() {
        return this.queuedAbility != null;
    }
    
    /**
     * Get the currently queued ability name
     * @return the queued ability name, or null if none
     */
    public String getQueuedAbility() {
        return this.queuedAbility;
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
        debugLog("Tick: " + getClientCycle());
        
        try {
            // Check for queued ability first - use it and clear the queue
            if (queuedAbility != null) {
                String ability = queuedAbility;
                queuedAbility = null; // Clear queue after retrieving
                
                // Don't fire queued Threads of Fate if NPCs dropped below threshold
                if (ability.equals("Threads of Fate") && nearbyNpcCount < 3) {
                    debugLog("= Skipping queued Threads of Fate (only " + nearbyNpcCount + " NPCs nearby)");
                    // Fall through to normal improvise
                } else {
                
                debugLog("= Using QUEUED ability: " + ability);
                
                // Update ability sequence tracking
                previousAbilityUsed = lastAbilityUsed;
                lastAbilityUsed = ability;
                
                boolean success = useAbility(ability);
                debugLog(success ? "+ Queued ability cast was successful" : "- Queued ability cast was unsuccessful");
                
                if (success) {
                    recordAbilityUse(ability);
                    updateTimer();
                }
                
                return success;
                }
            }
            
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
            
            // Update ability sequence tracking
            previousAbilityUsed = lastAbilityUsed;
            lastAbilityUsed = ability;
            
            boolean success = useAbility(ability);
            debugLog(success ? "+ Ability cast was successful" : "- Ability cast was unsuccessful");
            
            if (success) {
                // Record ability use for manual cooldown tracking
                recordAbilityUse(ability);
                updateTimer();
                
                // Log ability sequence for debugging
                debugLog("= Ability sequence: " + previousAbilityUsed + " -> " + ability);
                
                // Validate previous ability was actually used (if not first ability)
                if (!previousAbilityUsed.equals("None")) {
                    validatePreviousAbilityUse();
                }
                
                // Fire callback if Life Transfer was used
                if (ability.equals("Life Transfer") && onLifeTransferUsed != null) {
                    debugLog("[CALLBACK] Life Transfer used - firing callback");
                    onLifeTransferUsed.run();
                }
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
            // Basic Attack needs special handling via component click
            if (abilityName.equals("Basic<nbsp>Attack")) {
                if (basicAttackHandler != null) {
                    return basicAttackHandler.get();
                }
                return ActionBar.useAbility(abilityName); // Fallback
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
       int realGcd = gcdTicksRemaining();
       long ticksSinceLast = serverTick - lastExecutionTick;

       if (realGcd > 1) {
           debugLog("[GCD] Blocked - real GCD " + realGcd + " ticks remaining. Tick: " + serverTick);
           return false;
       }
       if (ticksSinceLast < 3) {
           debugLog("[GCD] Blocked - only " + ticksSinceLast + " cycles since last ability (need 3). Tick: " + serverTick);
           return false;
       }
       return true;
   }
    public static int gcdTicksRemaining() {
        int endCycle = VarManager.getVarc(2092);
        if (endCycle < 0) return 0;
        int millis = (endCycle - getClientCycle()) * 20;
        if (millis <= 500) return 0;
        return Math.max(0, (int) Math.ceil(millis / 600.0));
    }
    
    private void updateTimer() {
        lastExecutionTick = serverTick;
        debugLog("= Timer updated at server tick: " + lastExecutionTick);
    }
    
    /**
     * Sync the GCD timer from an external ability cast (e.g. SkeletonScript using an ability directly).
     * Call this whenever an ability is used outside of RotationManager.execute() to prevent
     * the rotation from firing on the same tick.
     */
    public void syncGCD() {
        lastExecutionTick = serverTick;
        debugLog("[GCD] Synced from external cast at server tick: " + lastExecutionTick);
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
        debugLog("[IMPROV]: = Death Skulls CD:  " + deathSkullsCooldown + " (real: " + ActionBar.getCooldown("Death Skulls") + ")");
        debugLog("[IMPROV]: = Living Death CD:  " + livingDeathCooldown + " (real: " + ActionBar.getCooldown("Living Death") + ")");
        debugLog("[IMPROV]: = Server Tick:      " + serverTick);
        debugLog("[IMPROV]: = Weapon Spec CD:   " + (serverTick - lastWeaponSpecialTick) + "/100");
        debugLog("[IMPROV]: = EOF CD:           " + (serverTick - lastEssenceOfFinalityTick) + "/50");
        debugLog("[IMPROV]: = Split Soul CD:    " + getAbilityCooldown("Split Soul") + " (real: " + ActionBar.getCooldown("Split Soul") + ")");
        debugLog("[IMPROV]: = Touch of Death CD:" + getAbilityCooldown("Touch of Death") + " (real: " + ActionBar.getCooldown("Touch of Death") + ")");
        debugLog("[IMPROV]: = Life Transfer CD: " + getAbilityCooldown("Life Transfer") + " (real: " + ActionBar.getCooldown("Life Transfer") + ")");
        debugLog("[IMPROV]: = Summons Active:   " + (armyConjureStatus == 1) + " (status: " + armyConjureStatus + ")");
        debugLog("[IMPROV]: = Basics Only:      " + basicsOnly);
        
        // Basics only mode - only use Soul Sap, Touch of Death, Basic Attack
        if (basicsOnly) {
            debugLog("[IMPROV]: Basics only mode - building adrenaline");
            if (isAbilityReady("Soul Sap")) {
                ability = "Soul Sap";
                debugLog("[IMPROV]: Basics - Soul Sap");
                return ability;
            } else if (isAbilityReady("Touch of Death")) {
                ability = "Touch of Death";
                debugLog("[IMPROV]: Basics - Touch of Death");
                return ability;
            } else {
                ability = "Basic<nbsp>Attack";
                debugLog("[IMPROV]: Basics - Basic Attack");
                return ability;
            }
        }

        // Threads of Fate AoE rotation - prioritize damage abilities for the 3 AoE charges
        // Auto-expire charges if too much time has passed (target died, abilities failed, etc.)
        if (threadsOfFateCharges > 0) {
            // Expire charges if 4 ticks passed since last charge was consumed (target likely dead)
            int expiryTick = lastThreadsChargeTick > 0 ? lastThreadsChargeTick : (lastUsedTick.getOrDefault("Threads of Fate", 0));
            if (serverTick - expiryTick > 4) {
                debugLog("[THREADS] Charges expired (4 ticks since last charge) - clearing");
                threadsOfFateCharges = 0;
            }
        }
        if (threadsOfFateCharges > 0) {
            debugLog("[IMPROV]: Threads of Fate AoE rotation (" + threadsOfFateCharges + " charges remaining)");
            
            // If target is dead, clear charges and fall through to normal rotation
            if (targetHealth <= 0) {
                debugLog("[THREADS] Target dead - clearing charges, resuming normal rotation");
                threadsOfFateCharges = 0;
            } else {
            
            if (useVolleyOfSouls && soulStacks >= 3 && isAbilityReady("Volley of Souls")) {
                ability = "Volley of Souls";
                debugLog("[IMPROV]: Threads - Volley of Souls (stacks: " + soulStacks + ")");
                return ability;
            }
            if (isAbilityReady("Soul Sap")) {
                ability = "Soul Sap";
                debugLog("[IMPROV]: Threads - Soul Sap");
                return ability;
            }
            if (necrosisStacks >= 6 && isAbilityReady("Finger of Death")) {
                ability = "Finger of Death";
                debugLog("[IMPROV]: Threads - Finger of Death");
                return ability;
            }
            if (isAbilityReady("Touch of Death")) {
                ability = "Touch of Death";
                debugLog("[IMPROV]: Threads - Touch of Death");
                return ability;
            }
            if (useWeaponSpecial && isAbilityReady("Weapon Special Attack") && adrenaline >= 27
                    && (serverTick - lastWeaponSpecialTick >= 100)) {
                ability = "Weapon Special Attack";
                lastWeaponSpecialTick = serverTick;
                debugLog("[IMPROV]: Threads - Weapon Special Attack");
                return ability;
            }
            if (useEssenceOfFinality && isAbilityReady("Essence of Finality") && adrenaline >= 23
                    && (serverTick - lastEssenceOfFinalityTick >= 50)) {
                ability = "Essence of Finality";
                lastEssenceOfFinalityTick = serverTick;
                debugLog("[IMPROV]: Threads - Essence of Finality");
                return ability;
            }
            ability = "Basic<nbsp>Attack";
            debugLog("[IMPROV]: Threads - Basic Attack (fallback)");
            return ability;
            }
        }
        
        if (livingDeath) {
            debugLog("[IMPROV]: Using Living Death rotation");
            // Living Death rotation
            if (useDeathSkulls && isAbilityReady("Death Skulls") && adrenaline >= 60) {
                ability = "Death Skulls";
                debugLog("[IMPROV]: Living Death - Death Skulls");
            } else {
                // Debug why Death Skulls wasn't used
                if (!useDeathSkulls) {
                    debugLog("[IMPROV]: Death Skulls disabled");
                } else if (isAbilityReady("Death Skulls")) {
                    debugLog("[IMPROV]: Death Skulls ready but insufficient adrenaline (" + adrenaline + "/60)");
                } else {
                    debugLog("[IMPROV]: Death Skulls not ready (CD: " + getAbilityCooldown("Death Skulls") + ")");
                }
                
                // Continue with other Living Death abilities
                if (isAbilityReady("Touch of Death") && adrenaline < 60) {
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
            }
        } else {
            debugLog("[IMPROV]: Using Normal rotation");
            
            // Check each ability individually with error handling
            try {
                if (useDeathSkulls && isAbilityReady("Death Skulls") && adrenaline >= 60) {
                    ability = "Death Skulls";
                    debugLog("[IMPROV]: Normal - Death Skulls");
                    return ability;
                } else if (!useDeathSkulls) {
                    debugLog("[IMPROV]: Death Skulls disabled");
                } else if (isAbilityReady("Death Skulls")) {
                    debugLog("[IMPROV]: Death Skulls ready but insufficient adrenaline (" + adrenaline + "/60)");
                } else {
                    debugLog("[IMPROV]: Death Skulls not ready (CD: " + getAbilityCooldown("Death Skulls") + ")");
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
                if (useThreadsOfFate && nearbyNpcCount >= 3 && isAbilityReady("Threads of Fate")) {
                    // Use Invoke Death first if ready, then queue Threads of Fate
                    if (isAbilityReady("Invoke Death")) {
                        ability = "Invoke Death";
                        queuedAbility = "Threads of Fate";
                        debugLog("[IMPROV]: Normal - Invoke Death (Threads of Fate queued, " + nearbyNpcCount + " NPCs nearby)");
                        return ability;
                    }
                    ability = "Threads of Fate";
                    debugLog("[IMPROV]: Normal - Threads of Fate (" + nearbyNpcCount + " NPCs nearby)");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Threads of Fate check: " + e.getMessage()); }
            
            try {
                if (useLivingDeath && spend && targetHealth > 20000 && isAbilityReady("Living Death") && adrenaline >= 100) {
                    ability = "Living Death";
                    debugLog("[IMPROV]: Normal - Living Death");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Living Death check: " + e.getMessage()); }
            
            try {
                if (useVolleyOfSouls && soulStacks >= 5 && isAbilityReady("Volley of Souls")) {
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
                if (useWeaponSpecial && isAbilityReady("Weapon Special Attack") && adrenaline >= 27 &&
                    (adrenaline != 100 || livingDeathCooldown < 10) && necrosisStacks >= 4 &&
                    (serverTick - lastWeaponSpecialTick >= 100)) {
                    ability = "Weapon Special Attack";
                    lastWeaponSpecialTick = serverTick;
                    debugLog("[IMPROV]: Normal - Special Attack (used at tick " + serverTick + ")");
                    return ability;
                }
            } catch (Exception e) { debugLog("[ERROR] Weapon Special check: " + e.getMessage()); }
            
            try {
                if (useEssenceOfFinality && isAbilityReady("Essence of Finality") && adrenaline >= 23 &&
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
                    // Skip during Threads of Fate AoE charges (waste of AoE hit)
                    if (threadsOfFateCharges > 0) {
                        debugLog("[IMPROV]: Skipping Conjure Army - Threads of Fate AoE active (" + threadsOfFateCharges + " charges)");
                    } else {
                        ability = "Conjure Undead Army";
                        lastConjureArmyTick = serverTick;
                        debugLog("[IMPROV]: Normal - Conjure Army");
                        return ability;
                    }
                }
            } catch (Exception e) { debugLog("[ERROR] Conjure Army check: " + e.getMessage()); }
            
            try {
                // Don't use Life Transfer if Conjure Army is almost ready (within 5 seconds / 8 ticks)
                int armyCooldown = getAbilityCooldown("Conjure Undead Army");
                boolean armyAlmostReady = armyCooldown > 0 && armyCooldown <= 8;
                
                if (useLifeTransfer && isAbilityReady("Life Transfer") && health > 9000 && !armyAlmostReady) {
                    // Skip during Threads of Fate AoE charges
                    if (threadsOfFateCharges > 0) {
                        debugLog("[IMPROV]: Skipping Life Transfer - Threads of Fate AoE active (" + threadsOfFateCharges + " charges)");
                    } else {
                        ability = "Life Transfer";
                        debugLog("[IMPROV]: Normal - Life Transfer");
                        return ability;
                    }
                } else if (!useLifeTransfer) {
                    debugLog("[IMPROV]: Life Transfer disabled");
                } else if (isAbilityReady("Life Transfer") && health > 9000 && armyAlmostReady) {
                    debugLog("[IMPROV]: Skipping Life Transfer - Conjure Army ready in " + armyCooldown + " ticks");
                }
            } catch (Exception e) { debugLog("[ERROR] Life Transfer check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Command Skeleton Warrior") && armyConjureStatus == 1) {
                    // Skip during Threads of Fate AoE charges
                    if (threadsOfFateCharges > 0) {
                        debugLog("[IMPROV]: Skipping Command Skeleton - Threads of Fate AoE active (" + threadsOfFateCharges + " charges)");
                    } else {
                        ability = "Command Skeleton Warrior";
                        debugLog("[IMPROV]: Normal - Command Skeleton");
                        return ability;
                    }
                }
            } catch (Exception e) { debugLog("[ERROR] Command Skeleton check: " + e.getMessage()); }
            
            try {
                if (isAbilityReady("Command Vengeful Ghost") && armyConjureStatus == 1 && !commandGhostUsedThisSummon) {
                    // Skip during Threads of Fate AoE charges
                    if (threadsOfFateCharges > 0) {
                        debugLog("[IMPROV]: Skipping Command Ghost - Threads of Fate AoE active (" + threadsOfFateCharges + " charges)");
                    } else {
                        ability = "Command Vengeful Ghost";
                        commandGhostUsedThisSummon = true;
                        debugLog("[IMPROV]: Normal - Command Ghost");
                        return ability;
                    }
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
                
                if (useLifeTransfer && isAbilityReady("Life Transfer") && health > 8000 && !armyAlmostReady) {
                    // Skip during Threads of Fate AoE charges
                    if (threadsOfFateCharges > 0) {
                        debugLog("[IMPROV]: Skipping Life Transfer (secondary) - Threads of Fate AoE active (" + threadsOfFateCharges + " charges)");
                    } else {
                        ability = "Life Transfer";
                        debugLog("[IMPROV]: Normal - Life Transfer (secondary)");
                        return ability;
                    }
                } else if (!useLifeTransfer) {
                    debugLog("[IMPROV]: Life Transfer disabled (secondary check)");
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
        this.lastAbilityUsed = "None";
        this.previousAbilityUsed = "None";
        this.threadsOfFateCharges = 0;
        this.lastThreadsChargeTick = -1;
        debugLog("Rotation reset - ability sequence cleared");
    }

        public void resetDeathMark() {
        this.invokeDeathBuffActive = false;
        debugLog("[DEATH MARK] Reset - ready to apply to new target");
    }
    

    
    public String getLastAbilityUsed() {
        return lastAbilityUsed;
    }
    
    /**
     * Get the previously used ability
     * @return the name of the ability used before the current one
     */
    public String getPreviousAbilityUsed() {
        return previousAbilityUsed;
    }
    
    /**
     * Get the cooldown of the previous ability to verify it was actually used
     * @return remaining cooldown in ticks, or -1 if no previous ability
     */
    public int getPreviousAbilityCooldown() {
        if (previousAbilityUsed.equals("None")) {
            return -1;
        }
        return getAbilityCooldown(previousAbilityUsed);
    }
    
    /**
     * Check if the previous ability is currently on cooldown (indicating it was used)
     * @return true if previous ability is on cooldown, false if ready or no previous ability
     */
    public boolean isPreviousAbilityOnCooldown() {
        if (previousAbilityUsed.equals("None")) {
            return false;
        }
        return getAbilityCooldown(previousAbilityUsed) > 0;
    }
    
    /**
     * Check if an ability is ready, with context about the previous ability used
     * This provides additional validation that the rotation is working as expected
     * @param abilityName the ability to check
     * @return true if the ability is ready
     */
    public boolean isAbilityReadyWithContext(String abilityName) {
        boolean ready = isAbilityReady(abilityName);
        
        if (debug && !previousAbilityUsed.equals("None")) {
            int previousCooldown = getAbilityCooldown(previousAbilityUsed);
            debugLog("[CONTEXT] Checking " + abilityName + " readiness after " + previousAbilityUsed + 
                    " (prev CD: " + previousCooldown + " ticks) -> " + (ready ? "READY" : "NOT READY"));
        }
        
        return ready;
    }
    
    /**
     * Get detailed ability sequence information for debugging
     * @return formatted string with current rotation state
     */
    public String getRotationSequenceInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Sequence: ").append(previousAbilityUsed).append(" -> ").append(lastAbilityUsed);
        
        if (!previousAbilityUsed.equals("None")) {
            int prevCooldown = getAbilityCooldown(previousAbilityUsed);
            Integer maxCooldown = ABILITY_COOLDOWNS.get(previousAbilityUsed);
            if (maxCooldown != null && maxCooldown > 0) {
                info.append(" (prev CD: ").append(prevCooldown).append("/").append(maxCooldown).append(")");
            }
        }
        
        return info.toString();
    }
    
    /**
     * Get the cooldown of an ability (public method for GUI)
     * @param abilityName the ability to check
     * @return remaining cooldown in ticks, or 0 if ready
     */
    public int getPublicAbilityCooldown(String abilityName) {
        // The core getAbilityCooldown method now handles auto-validation
        return getAbilityCooldown(abilityName);
    }
    
    /**
     * Reset cooldown tracking for a specific ability (for manual correction)
     */
    public void resetAbilityCooldown(String abilityName) {
        lastUsedTick.remove(abilityName);
        debugLog("[MANUAL RESET] " + abilityName + " cooldown reset - now available");
    }
    
    /**
     * Get detailed cooldown information for debugging
     */
    public String getDetailedCooldownInfo(String abilityName) {
        Integer lastUsed = lastUsedTick.get(abilityName);
        if (lastUsed == null) {
            return abilityName + ": Never used (Ready)";
        }
        
        int ticksSinceUse = serverTick - lastUsed;
        int cooldown = getAbilityCooldown(abilityName);
        Integer maxCooldown = ABILITY_COOLDOWNS.get(abilityName);
        
        // Special case for Death Skulls during Living Death
        if (abilityName.equals("Death Skulls") && maxCooldown != null) {
            Integer livingDeathUsed = lastUsedTick.get("Living Death");
            if (livingDeathUsed != null) {
                int ticksSinceLivingDeath = serverTick - livingDeathUsed;
                boolean livingDeathActive = ticksSinceLivingDeath < 50;
                if (livingDeathActive || deathSkullsUsedDuringLD) {
                    maxCooldown = 20;
                }
            }
        }
        
        return abilityName + ": Used " + ticksSinceUse + " ticks ago, " + cooldown + "/" + maxCooldown + " remaining";
    }
    
    /**
     * Manually validate and clean up all ability cooldowns
     * This can be called to fix stuck cooldowns
     */
    public void validateAllAbilities() {
        debugLog("[MANUAL VALIDATION] Checking all tracked abilities...");
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        
        for (java.util.Map.Entry<String, Integer> entry : lastUsedTick.entrySet()) {
            String abilityName = entry.getKey();
            int lastUsed = entry.getValue();
            int ticksSinceUse = serverTick - lastUsed;
            
            Integer maxCooldown = ABILITY_COOLDOWNS.get(abilityName);
            if (maxCooldown != null && maxCooldown > 0) {
                // Special handling for Death Skulls during Living Death
                int expectedCooldown = maxCooldown;
                if (abilityName.equals("Death Skulls")) {
                    Integer livingDeathUsed = lastUsedTick.get("Living Death");
                    if (livingDeathUsed != null) {
                        int ticksSinceLivingDeath = serverTick - livingDeathUsed;
                        boolean livingDeathActive = ticksSinceLivingDeath < 50;
                        if (livingDeathActive || deathSkullsUsedDuringLD) {
                            expectedCooldown = 20;
                        }
                    }
                }
                
                if (ticksSinceUse >= expectedCooldown) {
                    debugLog("[MANUAL VALIDATION] " + abilityName + " should be ready (used " + ticksSinceUse + " ticks ago, expected CD: " + expectedCooldown + ")");
                    toRemove.add(abilityName);
                }
            }
        }
        
        for (String abilityName : toRemove) {
            lastUsedTick.remove(abilityName);
            debugLog("[MANUAL VALIDATION] → Removed " + abilityName + " from cooldown tracking");
        }
        
        debugLog("[MANUAL VALIDATION] Validation complete - removed " + toRemove.size() + " abilities");
    }
    
    /**
     * Validate that the previous ability actually fired by checking its real in-game cooldown.
     * When ability B is used, check ability A's real cooldown via ActionBar.getCooldown.
     * If A's real cooldown is 0 but our internal tracking says it's on CD, A didn't fire
     * (e.g. mob died) — clear its tracking so improvise can pick it up again.
     */
    
        private void validatePreviousAbilityUse() {
            if (previousAbilityUsed.equals("None") || previousAbilityUsed.equals("Basic<nbsp>Attack")) {
                return;
            }

            Integer baseCooldown = ABILITY_COOLDOWNS.get(previousAbilityUsed);

            // Only validate abilities that have a real cooldown
            if (baseCooldown == null || baseCooldown == 0) {
                return;
            }

            // Check the real in-game cooldown via ActionBar
            int realCooldown = ActionBar.getCooldown(previousAbilityUsed);
            int internalCooldown = getAbilityCooldown(previousAbilityUsed);
            
            if (realCooldown <= 0 && internalCooldown > 0) {
                // Game says ready, our tracking says on CD — ability didn't fire
                debugLog("[VALIDATION] ⚠ " + previousAbilityUsed + " real CD=0 but internal CD=" 
                    + internalCooldown + " — didn't fire, clearing tracking");
                lastUsedTick.remove(previousAbilityUsed);
            } else if (realCooldown > 0) {
                debugLog("[VALIDATION] ✓ " + previousAbilityUsed + " confirmed on CD (real: " + realCooldown + ", internal: " + internalCooldown + ")");
            }
        }


    
    public void setUseAdrenalineRenewal(boolean useAdrenalineRenewal) {
        this.useAdrenalineRenewal = useAdrenalineRenewal;
    }
    
    public boolean isUseAdrenalineRenewal() {
        return useAdrenalineRenewal;
    }
    
    public void setUseEssenceOfFinality(boolean useEssenceOfFinality) {
        this.useEssenceOfFinality = useEssenceOfFinality;
    }
    
    public boolean isUseEssenceOfFinality() {
        return useEssenceOfFinality;
    }
    
    public void setUseWeaponSpecial(boolean useWeaponSpecial) {
        this.useWeaponSpecial = useWeaponSpecial;
    }
    
    public boolean isUseWeaponSpecial() {
        return useWeaponSpecial;
    }
    
    public void setUseLivingDeath(boolean useLivingDeath) {
        this.useLivingDeath = useLivingDeath;
    }
    
    public boolean isUseLivingDeath() {
        return useLivingDeath;
    }
    
    public void setUseSplitSoul(boolean useSplitSoul) {
        this.useSplitSoul = useSplitSoul;
    }
    
    public boolean isUseSplitSoul() {
        return useSplitSoul;
    }
    
    public void setUseDeathSkulls(boolean useDeathSkulls) {
        this.useDeathSkulls = useDeathSkulls;
    }
    
    public boolean isUseVolleyOfSouls() {
        return useVolleyOfSouls;
    }
    
    public void setUseVolleyOfSouls(boolean useVolleyOfSouls) {
        this.useVolleyOfSouls = useVolleyOfSouls;
    }
    
    public boolean isUseDeathSkulls() {
        return useDeathSkulls;
    }
    
    /**
     * Set basics only mode - when true, only uses Soul Sap, Touch of Death, Basic Attack
     * Used for building adrenaline without spending stacks
     */
    public void setBasicsOnly(boolean basicsOnly) {
        this.basicsOnly = basicsOnly;
    }
    
    public boolean isBasicsOnly() {
        return basicsOnly;
    }
    
    /**
     * Set whether to use Life Transfer ability
     * @param useLifeTransfer true to use Life Transfer, false to disable
     */
    public void setUseLifeTransfer(boolean useLifeTransfer) {
        this.useLifeTransfer = useLifeTransfer;
    }
    
    public boolean isUseLifeTransfer() {
        return useLifeTransfer;
    }
    
    public void setNearbyNpcCount(int count) {
        this.nearbyNpcCount = count;
    }
    
    public void setUseThreadsOfFate(boolean useThreadsOfFate) {
        this.useThreadsOfFate = useThreadsOfFate;
    }
    
    public boolean isUseThreadsOfFate() {
        return useThreadsOfFate;
    }

    /**
     * Get remaining ticks until Undead Army expires
     * @return remaining ticks, or 0 if army is not active
     */
    public int getUndeadArmyRemainingTicks() {
        if (undeadArmyExpiresTick <= serverTick) {
            return 0;
        }
        return undeadArmyExpiresTick - serverTick;
    }
    
    /**
     * Get remaining seconds until Undead Army expires
     * @return remaining seconds (approximate), or 0 if army is not active
     */
    public double getUndeadArmyRemainingSeconds() {
        return getUndeadArmyRemainingTicks() * 0.6;
    }
    
    /**
     * Check if Undead Army is currently active
     * @return true if army is active
     */
    public boolean isUndeadArmyActive() {
        return undeadArmyExpiresTick > serverTick;
    }
    
    /**
     * Get debug info about army duration tracking
     * @return debug string with expiry tick and current tick
     */
    public String getUndeadArmyDebugInfo() {
        return "expiresTick=" + undeadArmyExpiresTick + ", serverTick=" + serverTick + ", remaining=" + getUndeadArmyRemainingTicks();
    }
    
    /**
     * Get ticks since last Conjure Undead Army was cast
     */
    public int getTicksSinceLastConjure() {
        if (lastConjureArmyTick < 0) return Integer.MAX_VALUE;
        return serverTick - lastConjureArmyTick;
    }
    
    /**
     * Set a callback to be called when Life Transfer is used
     * @param callback the callback to run when Life Transfer is used
     */
    public void setOnLifeTransferUsed(Runnable callback) {
        this.onLifeTransferUsed = callback;
    }
    
    public void setBasicAttackHandler(java.util.function.Supplier<Boolean> handler) {
        this.basicAttackHandler = handler;
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
                invokeDeathBuffTick = -1;
                return false; // Already applied, don't consume GCD
            }
            
            // If Invoke Death buff is active, check for timeout (10 ticks = 6 seconds)
            // This prevents the buff from getting stuck if target dies or changes
            if (invokeDeathBuffActive) {
                if (invokeDeathBuffTick > 0 && (serverTick - invokeDeathBuffTick) > 10) {
                    debugLog("[DEATH MARK] Invoke Death buff timed out - resetting");
                    invokeDeathBuffActive = false;
                    invokeDeathBuffTick = -1;
                } else {
                    debugLog("[DEATH MARK] Invoke Death buff active, waiting for next attack to apply");
                    return false; // Don't recast, let normal rotation continue
                }
            }
            
            // Target not death marked, use Invoke Death ability directly
            // Skip slot cache check since Invoke Death is critical for priority targets
            debugLog("[DEATH MARK] Target not death marked, using Invoke Death");
            boolean success = ActionBar.useAbility("Invoke Death");
            
            if (success) {
                recordAbilityUse("Invoke Death");
                updateTimer(); // Update GCD timer so rotation knows ability was used
                invokeDeathBuffActive = true; // Buff is now active
                invokeDeathBuffTick = serverTick; // Track when buff was activated
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
    private int invokeDeathBuffTick = -1; // Track when buff was activated (for timeout)
    
    // Setting: Use Adrenaline Renewal with Living Death

    
    // Setting: Use Split Soul
    private boolean useSplitSoul = false; // Default disabled
    
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
        
        // AUTO-VALIDATION: If enough time has passed, remove from tracking
        if (remaining <= 0) {
            debugLog("[AUTO-VALIDATION] " + abilityName + " cooldown expired (used " + ticksSinceUse + " ticks ago, CD: " + maxCooldown + ") - removing from tracking");
            lastUsedTick.remove(abilityName);
            return 0;
        }
        
        return remaining;
    }
    
    /**
     * Check if an ability is ready based on manual cooldown tracking
     */
    private boolean isAbilityReady(String abilityName) {
        // Check if ability exists in cache
        if (slotCacheInitialized && !slotCache.containsKey(abilityName)) {
            debugLog("[READY CHECK] " + abilityName + " not in action bar cache");
            return false;
        }
        
        // Check manual cooldown
        int cooldown = getAbilityCooldown(abilityName);
        boolean ready = cooldown <= 1;
        if (debug && !ready) {
            debugLog("[READY CHECK] " + abilityName + " on cooldown (" + cooldown + " ticks remaining)");
        }
        return ready;
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
     * Record that an ability was used at the current server tick (public wrapper)
     * Call this when using abilities outside of the RotationManager's execute() method
     * @param abilityName Name of the ability that was used
     */
    public void recordAbilityUsed(String abilityName) {
        recordAbilityUse(abilityName);
    }
    
    /**
     * Check if an ability is ready to use (public wrapper)
     * @param abilityName Name of the ability to check
     * @return true if the ability is ready (not on cooldown)
     */
    public boolean isAbilityReadyPublic(String abilityName) {
        return isAbilityReady(abilityName);
    }
    
    /**
     * Record that an ability was used at the current server tick
     */
    private void recordAbilityUse(String abilityName) {
        lastUsedTick.put(abilityName, serverTick);
        debugLog("[COOLDOWN] Recorded " + abilityName + " used at tick " + serverTick);
        
        // Threads of Fate: set 3 AoE charges when used, decrement on other abilities
        if (abilityName.equals("Threads of Fate")) {
            threadsOfFateCharges = 3;
            lastThreadsChargeTick = serverTick;
            debugLog("[THREADS] Threads of Fate used - 3 AoE charges active");
        } else if (threadsOfFateCharges > 0) {
            threadsOfFateCharges--;
            lastThreadsChargeTick = serverTick;
            debugLog("[THREADS] AoE charge consumed by " + abilityName + " (" + threadsOfFateCharges + " remaining)");
        }
        
        // Special case: Command Vengeful Ghost sets the used flag
        if (abilityName.equals("Command Vengeful Ghost")) {
            commandGhostUsedThisSummon = true;
            debugLog("[COOLDOWN] Command Ghost used this summon");
        }
        
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
            lastConjureArmyTick = serverTick;
            commandGhostUsedThisSummon = false;
            // Command abilities get 3.6 second (6 tick) cooldown when army is conjured
            // Set both to current tick - they'll have 6 tick delay via special logic
            lastUsedTick.put("Command Skeleton Warrior", serverTick - 19); // Will be ready in 6 ticks (25 - 19 = 6)
            lastUsedTick.put("Command Vengeful Ghost", serverTick); // Will be ready in 6 ticks (special case in getAbilityCooldown)
            lastUsedTick.put("Command Putrid Zombie", serverTick - 19); // Will be ready in 6 ticks (25 - 19 = 6)
            // Set army expiry time
            undeadArmyExpiresTick = serverTick + UNDEAD_ARMY_BASE_DURATION;
            debugLog("[COOLDOWN] Conjure Army reset Command Ghost flag and set Command abilities on 6 tick cooldown");
            debugLog("[COOLDOWN] Undead Army expires at tick " + undeadArmyExpiresTick + " (" + UNDEAD_ARMY_BASE_DURATION + " ticks)");
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
            // Extend army expiry time if army is active
            if (undeadArmyExpiresTick > serverTick) {
                undeadArmyExpiresTick += UNDEAD_ARMY_LIFE_TRANSFER_EXTENSION;
                debugLog("[COOLDOWN] Life Transfer extended Undead Army to tick " + undeadArmyExpiresTick);
            }
        }
    }
}

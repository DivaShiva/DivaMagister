package net.botwithus;


import net.botwithus.internal.scripts.ScriptDefinition;
import net.botwithus.rs3.events.impl.ServerTickedEvent;
import net.botwithus.rs3.game.Client;
import net.botwithus.rs3.game.hud.interfaces.Interfaces;
import net.botwithus.rs3.game.inventories.Backpack;
import net.botwithus.rs3.game.js5.types.vars.VarDomainType;
import net.botwithus.rs3.game.queries.builders.components.ComponentQuery;
import net.botwithus.rs3.game.queries.builders.objects.SceneObjectQuery;
import net.botwithus.rs3.game.hud.interfaces.Component;
import net.botwithus.rs3.game.scene.entities.characters.player.LocalPlayer;

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
    private Random random = new Random();
    private int lastLoggedClientCycle = 0;
    private int serverTicks = 0;
    private int livingDeathActivatedServerTick = -1;
    private int lastAbilityServerTick = 0;
    private RotationManager rotation;

    enum BotState {
        //define your own states here
        IDLE,
        SKILLING,
        BANKING,
        //...
    }

    public SkeletonScript(String s, ScriptConfig scriptConfig, ScriptDefinition scriptDefinition) {
        super(s, scriptConfig, scriptDefinition);
        this.sgc = new SkeletonScriptGraphicsContext(getConsole(), this);
        
        // Initialize improvise rotation
        rotation = new RotationManager("Necromancy Improvise", true); // true = spend adrenaline
        rotation.setDebug(true);
        rotation.setLogger(this::println); // Use script's println for logging
        
        // Subscribe to server tick events
        subscribe(ServerTickedEvent.class, event -> {
            try {
                serverTicks = event.getTicks();
                rotation.setServerTick(serverTicks);
                
                // Only execute rotation/logging when script is active AND in SKILLING state
                if (isActive() && botState == BotState.SKILLING) {
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
        // Only execute when in SKILLING state
        if (botState != BotState.SKILLING) {
            return;
        }
        
        // Execute every 3 server ticks (1.8 seconds)
        if (serverTicks - lastAbilityServerTick >= 3) {
            if (rotation.execute()) {
                lastAbilityServerTick = serverTicks;
                String ability = rotation.getLastAbilityUsed();
                println("Tick " + serverTicks + " - Using: " + ability);
            }
        }
    }
    
    private void checkAndLog() {
        try {
            // Only log when in SKILLING state
            if (botState != BotState.SKILLING) {
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
        //Loops every 100ms by default, to change:
        //this.loopDelay = 500;
        LocalPlayer player = Client.getLocalPlayer();
        if (player == null || Client.getGameState() != Client.GameState.LOGGED_IN || botState == BotState.IDLE) {
            //wait some time so we dont immediately start on login.
            Execution.delay(random.nextLong(3000,7000));
            return;
        }
        
        switch (botState) {
            case IDLE -> {
                //do nothing
                println("We're idle!");
                Execution.delay(random.nextLong(1000,3000));
            }
            case SKILLING -> {
                //do some code that handles your skilling
                Execution.delay(handleSkilling(player));
            }
            case BANKING -> {
                // Component queries disabled - they crash the client
                println("Banking state - component queries disabled");
                Execution.delay(random.nextLong(2000, 4000));
            }
        }
    }

    private long handleSkilling(LocalPlayer player) {
        //for example, if skilling progress interface is open, return a randomized value to keep waiting.

        return random.nextLong(1500,3000);
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

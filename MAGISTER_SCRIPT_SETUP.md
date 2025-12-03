# Magister Killer Script - Setup Guide

## What Was Changed

I've converted your SkeletonScript into a Magister killing bot that:

1. **Touches the Soul obelisk** to spawn The Magister
2. **Handles the dialog** that appears after touching
3. **Targets and kills The Magister** using the Necromancy rotation
4. **Repeats the cycle** automatically

## Script Flow

```
TOUCHING_OBELISK → HANDLING_DIALOG → FIGHTING_MAGISTER → WAITING_FOR_LOOT → (repeat)
```

### State Details:

- **TOUCHING_OBELISK**: Finds and touches the Soul obelisk
- **HANDLING_DIALOG**: Clicks through the dialog using MiniMenu (interface 1188, hash 77856776)
- **FIGHTING_MAGISTER**: Targets Magister and uses Necromancy rotation automatically
- **WAITING_FOR_LOOT**: Waits 2-3 seconds after kill, then restarts cycle

## How to Use

### 1. Install Java 20
**IMPORTANT**: The BotWithUs API requires Java 20. You currently have Java 11.

Download Java 20 from:
- https://adoptium.net/ (Temurin JDK 20)
- Or https://www.oracle.com/java/technologies/downloads/

After installing, verify with: `java -version`

### 2. Build the Script
```bash
./gradlew build
```

This will compile and copy the JAR to your BotWithUs scripts folder.

### 3. Setup in Game

1. **Load your Necromancy action bar** with all abilities
2. **Start the script** in BotWithUs
3. **Open the GUI** and go to the "Rotation" tab
4. **Click "Scan Action Bar"** - This is critical! The rotation won't work without it
5. **Verify** it found your abilities (check "Cached Abilities" count)

### 4. Start Killing

1. **Go to Magister arena** and stand near the Soul obelisk
2. **Open the GUI** → "Settings" tab
3. **Click "Start Magister"**
4. The script will now loop automatically

### 5. Optional Settings (Rotation Tab)

- ☑️ **Use vuln bombs?** - Auto-throw Vulnerability Bombs
- ☑️ **Use Death Mark?** - Auto-cast Invoke Death
- ☑️ **Drink Adrenaline Renewal?** - Auto-drink with Living Death

## Code Changes Made

### SkeletonScript.java
- Changed BotState enum to: IDLE, TOUCHING_OBELISK, HANDLING_DIALOG, FIGHTING_MAGISTER, WAITING_FOR_LOOT
- Added `handleTouchObelisk()` - Finds and touches Soul obelisk using SceneObjectQuery
- Added `handleDialog()` - Handles dialog using MiniMenu.interact() with ComponentAction.DIALOGUE
- Added `handleFighting()` - Queries for "The Magister (level: 899)" with "Attack" option and targets him
- Added `handleWaitingForLoot()` - Waits after kill, then restarts
- Updated rotation execution to only run in FIGHTING_MAGISTER state
- Added MiniMenu and ComponentAction imports
- Uses EntityResultSet pattern for all NPC queries

### SkeletonScriptGraphicsContext.java
- Updated GUI with "Start Magister" and "Stop (IDLE)" buttons
- Added instructions in Settings tab
- Kept Rotation tab for action bar scanning and options

### build.gradle.kts
- Added Java version configuration (needs Java 20)

## Troubleshooting

### "Soul obelisk not found"
- Make sure you're standing in the Magister arena
- The obelisk must be visible on screen

### "Abilities not working"
- Click "Scan Action Bar" in the Rotation tab
- Make sure all Necromancy abilities are on your action bar
- Check console for "Cached Abilities: X" count

### "Dialog not clicking"
- The script uses MiniMenu with interface 1188 and hash 77856776
- If the dialog changes, you may need to update the hash value

### Script stops after one kill
- Check console for errors
- Make sure you have enough supplies (food, prayer, etc.)
- The script doesn't handle banking yet

## Future Enhancements

You can add:
- **Loot pickup** in `handleWaitingForLoot()`
- **Food/prayer management** in `handleFighting()`
- **Banking** when supplies run low
- **Death handling** if you die
- **Key usage** (Vital spark tracking)

## Files Modified

- `src/main/java/net/botwithus/SkeletonScript.java` - Main script logic
- `src/main/java/net/botwithus/SkeletonScriptGraphicsContext.java` - GUI
- `build.gradle.kts` - Java version config

## Files Unchanged

- `src/main/java/net/botwithus/RotationManager.java` - Combat rotation (works as-is)
- `src/main/java/net/botwithus/SkeletonScriptExample.java` - Example file

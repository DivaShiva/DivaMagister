package net.botwithus;

import net.botwithus.rs3.imgui.ImGui;
import net.botwithus.rs3.imgui.ImGuiWindowFlag;
import net.botwithus.rs3.script.ScriptConsole;
import net.botwithus.rs3.script.ScriptGraphicsContext;

import java.util.List;

public class SkeletonScriptGraphicsContext extends ScriptGraphicsContext {

    private SkeletonScript script;
    private int activePage = 0; // 0=Settings, 1=Rotation, 2=Cooldowns, 3=Log

    private static final String[] PAGE_NAMES = {"Settings", "Rotation", "Cooldowns", "Log"};
    private static final float SIDEBAR_WIDTH = 120f;

    public SkeletonScriptGraphicsContext(ScriptConsole scriptConsole, SkeletonScript script) {
        super(scriptConsole);
        this.script = script;
    }

    @Override
    public void drawSettings() {
        ImGui.SetNextWindowSize(620f, 450f, 4); // 4 = ImGuiCond_FirstUseEver
        if (ImGui.Begin("DivaMagister", ImGuiWindowFlag.None.getValue())) {

            // --- Sidebar ---
            ImGui.BeginChild("Sidebar", SIDEBAR_WIDTH, 0f, true, ImGuiWindowFlag.None.getValue());
            for (int i = 0; i < PAGE_NAMES.length; i++) {
                boolean isActive = (i == activePage);
                if (ImGui.Selectable(PAGE_NAMES[i], isActive, 0)) {
                    activePage = i;
                }
            }

            // State indicator at bottom of sidebar
            ImGui.Separator();
            ImGui.Text("State:");
            ImGui.Text(script.getBotState().toString());

            ImGui.Separator();
            ImGui.Text("Kills: " + script.getKillCount());
            ImGui.Text(String.format("%.1f/hr", script.getKillsPerHour()));

            ImGui.Separator();
            if (ImGui.Selectable("Save Config", false, 0)) {
                script.saveConfig();
            }

            ImGui.EndChild();

            ImGui.SameLine();

            // --- Content area ---
            ImGui.BeginChild("Content", 0f, 0f, true, ImGuiWindowFlag.None.getValue());

            // Page content in scrollable area above the start button
            ImGui.BeginChild("PageContent", 0f, -35f, false, ImGuiWindowFlag.None.getValue());
            switch (activePage) {
                case 0 -> drawSettingsPage();
                case 1 -> drawRotationPage();
                case 2 -> drawCooldownsPage();
                case 3 -> drawLogPage();
            }
            ImGui.EndChild();

            // Start/Stop buttons pinned at bottom
            ImGui.Separator();
            boolean isRunning = script.getBotState() != SkeletonScript.BotState.IDLE;
            if (isRunning) {
                if (ImGui.Button("Stop")) {
                    script.setBotState(SkeletonScript.BotState.IDLE);
                }
            } else {
                if (ImGui.Button("Start Magister")) {
                    script.startScript();
                }
            }

            ImGui.EndChild();

            ImGui.End();
        }
    }

    // ==================== SETTINGS PAGE ====================
    private void drawSettingsPage() {
        ImGui.Text("Magister Killer Script");
        ImGui.Separator();

        ImGui.Text("Kill Statistics:");
        ImGui.Text("Total Kills: " + script.getKillCount());
        ImGui.Text("Kills/Hour: " + String.format("%.1f", script.getKillsPerHour()));
        ImGui.Text("Cumulative Loot Value: " + script.getCumulativeLootValue() + "K");
        if (ImGui.Button("Reset Kill Counter")) {
            script.resetKillCount();
        }

        ImGui.Separator();
        ImGui.Text("Instructions:");
        ImGui.Text("1. Go to Rotation tab and click 'Scan Action Bar'");
        ImGui.Text("2. Start in either Magister arena or War's Retreat");
        ImGui.Text("3. Click 'Start Magister' to begin");
    }

    // ==================== ROTATION PAGE ====================
    private void drawRotationPage() {
        ImGui.Text("Action Bar Scanner");
        ImGui.Separator();

        if (ImGui.Button("Scan Action Bar")) {
            script.scanActionBar();
        }
        ImGui.SameLine();
        if (ImGui.Button("Show Cached Slots")) {
            script.printCachedSlots();
        }
        ImGui.Text("Cached Abilities: " + script.getCachedAbilityCount());

        ImGui.Separator();
        ImGui.Text("Combat Options");

        script.setUseVulnBombs(ImGui.Checkbox("Use Vuln Bombs", script.isUseVulnBombs()));
        script.setUseDeathMark(ImGui.Checkbox("Use Death Mark", script.isUseDeathMark()));
        script.setUseDeathSkulls(ImGui.Checkbox("Use Death Skulls", script.isUseDeathSkulls()));
        script.setUseSplitSoul(ImGui.Checkbox("Use Split Soul", script.isUseSplitSoul()));
        script.setUseEssenceOfFinality(ImGui.Checkbox("Use Essence of Finality", script.isUseEssenceOfFinality()));
        script.setUseWeaponSpecial(ImGui.Checkbox("Use Weapon Special Attack", script.isUseWeaponSpecial()));
        script.setUseAdrenalineRenewal(ImGui.Checkbox("Drink Adrenaline Renewal", script.isUseAdrenalineRenewal()));

        ImGui.Separator();
        ImGui.Text("Utility");

        script.setUseWeaponPoison(ImGui.Checkbox("Apply Weapon Poison", script.isUseWeaponPoison()));
        script.setUsePocketSlot(ImGui.Checkbox("Activate Pocket Slot", script.isUsePocketSlot()));
        script.setUseFamiliar(ImGui.Checkbox("Manage Familiar", script.isUseFamiliar()));
    }

    // ==================== COOLDOWNS PAGE ====================
    private void drawCooldownsPage() {
        RotationManager rotation = script.getRotation();
        if (rotation == null) {
            ImGui.Text("Rotation Manager not initialized");
            return;
        }

        ImGui.Text("Current: " + rotation.getLastAbilityUsed());
        ImGui.SameLine();
        ImGui.Text("  Previous: " + rotation.getPreviousAbilityUsed());
        ImGui.Text(rotation.getRotationSequenceInfo());
        ImGui.Separator();

        String[] trackedAbilities = {
            "Death Skulls", "Split Soul", "Living Death", "Touch of Death",
            "Bloat", "Weapon Special Attack", "Essence of Finality",
            "Conjure Undead Army", "Life Transfer", "Command Skeleton Warrior",
            "Soul Sap"
        };

        ImGui.Columns(3, "CooldownColumns", true);
        ImGui.Text("Ability");
        ImGui.NextColumn();
        ImGui.Text("Cooldown");
        ImGui.NextColumn();
        ImGui.Text("Status");
        ImGui.NextColumn();
        ImGui.Separator();

        for (String ability : trackedAbilities) {
            int cooldown = rotation.getPublicAbilityCooldown(ability);
            boolean ready = cooldown <= 1;

            ImGui.Text(ability);
            ImGui.NextColumn();
            ImGui.Text(cooldown > 0 ? cooldown + " ticks" : "Ready");
            ImGui.NextColumn();

            if (ready) {
                ImGui.PushStyleColor(0, 0.2f, 0.8f, 0.2f, 1.0f); // green
                ImGui.Text("READY");
            } else {
                ImGui.PushStyleColor(0, 0.8f, 0.2f, 0.2f, 1.0f); // red
                ImGui.Text("ON CD");
            }
            ImGui.PopStyleColor(1);
            ImGui.NextColumn();
        }

        ImGui.Columns(1, "SingleColumn", false);
        ImGui.Separator();

        if (ImGui.Button("Validate All Cooldowns")) {
            rotation.validateAllAbilities();
        }
        ImGui.SameLine();
        ImGui.Text("Fix stuck cooldowns");
    }

    // ==================== LOG PAGE ====================
    private void drawLogPage() {
        final float reservedBottom = 30f;
        ImGui.BeginChild("LogScroll", 0f, -reservedBottom, true, ImGuiWindowFlag.None.getValue());

        List<String> texts = script.getGuiLogTexts();
        List<float[]> colors = script.getGuiLogColors();
        for (int i = 0; i < texts.size(); i++) {
            float[] c = colors.get(i);
            ImGui.PushStyleColor(0, c[0], c[1], c[2], 1.0f);
            ImGui.Text(texts.get(i));
            ImGui.PopStyleColor(1);
        }

        if (script.isAutoScroll()) {
            ImGui.SetScrollHereY(1.0f);
        }
        ImGui.EndChild();

        boolean autoOn = script.isAutoScroll();
        if (ImGui.Button(autoOn ? "Auto-scroll: ON" : "Auto-scroll: OFF")) {
            script.setAutoScroll(!autoOn);
        }
    }

    @Override
    public void drawOverlay() {
        super.drawOverlay();
    }
}

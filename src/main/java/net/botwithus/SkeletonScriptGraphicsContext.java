package net.botwithus;

import net.botwithus.rs3.imgui.ImGui;
import net.botwithus.rs3.imgui.ImGuiWindowFlag;
import net.botwithus.rs3.script.ScriptConsole;
import net.botwithus.rs3.script.ScriptGraphicsContext;

public class SkeletonScriptGraphicsContext extends ScriptGraphicsContext {

    private SkeletonScript script;

    public SkeletonScriptGraphicsContext(ScriptConsole scriptConsole, SkeletonScript script) {
        super(scriptConsole);
        this.script = script;
    }

    @Override
    public void drawSettings() {
        if (ImGui.Begin("My script", ImGuiWindowFlag.None.getValue())) {
            if (ImGui.BeginTabBar("My bar", ImGuiWindowFlag.None.getValue())) {
                if (ImGui.BeginTabItem("Settings", ImGuiWindowFlag.None.getValue())) {
                    ImGui.Text("Magister Killer Script");
                    ImGui.Text("Current State: " + script.getBotState());
                    ImGui.Separator();
                    
                    ImGui.Text("Script Control:");
                    if (ImGui.Button("Start Magister")) {
                        script.setBotState(SkeletonScript.BotState.TOUCHING_OBELISK);
                    }
                    ImGui.SameLine();
                    if (ImGui.Button("Stop (IDLE)")) {
                        script.setBotState(SkeletonScript.BotState.IDLE);
                    }
                    
                    ImGui.Separator();
                    ImGui.Text("Instructions:");
                    ImGui.Text("1. Go to Rotation tab and click 'Scan Action Bar'");
                    ImGui.Text("2. Stand near the Soul obelisk in Magister arena");
                    ImGui.Text("3. Click 'Start Magister' to begin");
                    ImGui.Text("4. Script will: Touch obelisk -> Handle dialog -> Kill Magister -> Repeat");
                    
                    ImGui.EndTabItem();
                }
                if (ImGui.BeginTabItem("Rotation", ImGuiWindowFlag.None.getValue())) {
                    ImGui.Text("Ability Bar Scanner");
                    ImGui.Separator();
                    
                    if (ImGui.Button("Scan Action Bar")) {
                        script.scanActionBar();
                    }
                    
                    ImGui.Text("Scans your action bar and caches ability positions.");
                    ImGui.Text("This reduces queries and prevents crashes.");
                    ImGui.Text("Run this once after setting up your action bar.");
                    

                    ImGui.Text("Cached Abilities: " + script.getCachedAbilityCount());
                    

                    if (ImGui.Button("Show Cached Slots")) {
                        script.printCachedSlots();
                    }
                    
                    ImGui.Separator();
                    ImGui.Text("Rotation Options");
                    script.setUseVulnBombs(ImGui.Checkbox("Use vuln bombs?", script.isUseVulnBombs()));
                    script.setUseDeathMark(ImGui.Checkbox("Use Death Mark?", script.isUseDeathMark()));
                    script.setUseAdrenalineRenewal(ImGui.Checkbox("Drink Adrenaline Renewal?", script.isUseAdrenalineRenewal()));
                    script.setUseSplitSoul(ImGui.Checkbox("Use Split Soul?", script.isUseSplitSoul()));
                    
                    ImGui.EndTabItem();
                }
                if (ImGui.BeginTabItem("Other", ImGuiWindowFlag.None.getValue())) {
                    script.setSomeBool(ImGui.Checkbox("Are you cool?", script.isSomeBool()));
                    ImGui.EndTabItem();
                }
                ImGui.EndTabBar();
            }
            ImGui.End();
        }

    }

    @Override
    public void drawOverlay() {
        super.drawOverlay();
    }
}

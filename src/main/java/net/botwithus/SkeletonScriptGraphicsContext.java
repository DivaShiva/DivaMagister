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
                    ImGui.Text("Welcome to my script!");
                    ImGui.Text("My scripts state is: " + script.getBotState());
                    ImGui.Separator();
                    
                    ImGui.Text("Set Bot State:");
                    if (ImGui.Button("IDLE")) {
                        script.setBotState(SkeletonScript.BotState.IDLE);
                    }
                    ImGui.SameLine();
                    if (ImGui.Button("SKILLING")) {
                        script.setBotState(SkeletonScript.BotState.SKILLING);
                    }
                    ImGui.SameLine();
                    if (ImGui.Button("BANKING")) {
                        script.setBotState(SkeletonScript.BotState.BANKING);
                    }
                    
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

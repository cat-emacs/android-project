package cat.android.project.tooling;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Wire-only model shared by the Gradle provider and client; no Gradle/AGP references. */
public interface TargetModel {
    List<Target> getTargets();
    String getBuildPath();

    interface Component {
        String getKind();
        String getName();
        List<String> getSourceRoots();
    }
    final class ComponentData implements Component, Serializable {
        public final String kind;
        public final String name;
        public final List<String> sourceRoots;
        public ComponentData(String kind, String name, List<String> sourceRoots) {
            this.kind = kind; this.name = name; this.sourceRoots = new ArrayList<>(sourceRoots);
        }
        public String getKind() { return kind; }
        public String getName() { return name; }
        public List<String> getSourceRoots() { return sourceRoots; }
    }

    interface Target {
        String getModulePath(); String getModuleRoot(); String getBuildRoot(); String getVariant();
        String getApplicationId(); String getBuildType(); String getPluginId(); String getTestApplicationId();
        String getNamespace(); String getProjectType(); List<String> getSourceRoots();
        List<String> getProductFlavors(); List<String> getPreferredProductFlavors();
        boolean getPreferredBuildType(); boolean getDebuggable(); List<Component> getComponents();
    }
    final class TargetData implements Target, Serializable {
        public final String modulePath, moduleRoot, buildRoot, variant, applicationId;
        public final String buildType, pluginId, testApplicationId, namespace, projectType;
        public final List<String> sourceRoots, productFlavors, preferredProductFlavors;
        public final boolean preferredBuildType, debuggable;
        public final List<Component> components;
        public TargetData(String modulePath, String moduleRoot, String buildRoot, String variant,
                      String applicationId, List<String> sourceRoots, String buildType,
                      List<String> productFlavors, boolean preferredBuildType,
                      List<String> preferredProductFlavors, String pluginId,
                      String testApplicationId, String namespace, List<Component> components,
                      String projectType, boolean debuggable) {
            this.modulePath = modulePath; this.moduleRoot = moduleRoot; this.buildRoot = buildRoot;
            this.variant = variant; this.applicationId = applicationId;
            this.sourceRoots = new ArrayList<>(sourceRoots); this.buildType = buildType;
            this.productFlavors = new ArrayList<>(productFlavors); this.preferredBuildType = preferredBuildType;
            this.preferredProductFlavors = new ArrayList<>(preferredProductFlavors); this.pluginId = pluginId;
            this.testApplicationId = testApplicationId; this.namespace = namespace;
            this.components = new ArrayList<>(components); this.projectType = projectType; this.debuggable = debuggable;
        }
        public String getModulePath() { return modulePath; }
        public String getModuleRoot() { return moduleRoot; }
        public String getBuildRoot() { return buildRoot; }
        public String getVariant() { return variant; }
        public String getApplicationId() { return applicationId; }
        public String getBuildType() { return buildType; }
        public String getPluginId() { return pluginId; }
        public String getTestApplicationId() { return testApplicationId; }
        public String getNamespace() { return namespace; }
        public String getProjectType() { return projectType; }
        public List<String> getSourceRoots() { return sourceRoots; }
        public List<String> getProductFlavors() { return productFlavors; }
        public List<String> getPreferredProductFlavors() { return preferredProductFlavors; }
        public boolean getPreferredBuildType() { return preferredBuildType; }
        public boolean getDebuggable() { return debuggable; }
        public List<Component> getComponents() { return components; }
    }
}

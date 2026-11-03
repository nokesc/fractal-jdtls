// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.agent;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.util.List;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * One hook: the class, the matcher that must select exactly one of its declared methods, and the
 * advice inlined into that method.
 *
 * @param id the hook's name in reports and the diagnostic log: the target method's name
 * @param type the target class's binary name
 * @param method the target method's signature, for reports
 * @param matcher selects the target method among the class's declared methods
 * @param advice the advice class, from {@link Advices}
 */
public record Hook(String id, String type, String method, ElementMatcher<? super MethodDescription> matcher,
        Class<?> advice) {

    private static final String M2E = "org.eclipse.m2e.core.internal.project.";
    private static final String MAVEN_MODEL = "org.apache.maven.model.";

    /**
     * The product's six hooks, and no others (design §6). The matchers name every parameter type,
     * so a changed signature after an upgrade selects no method and is reported.
     */
    public static List<Hook> all() {
        return List.of(
                new Hook("getProjectName", M2E + "ProjectConfigurationManager",
                        "static getProjectName(ProjectImportConfiguration, Model)",
                        signature("getProjectName", "org.eclipse.m2e.core.project.ProjectImportConfiguration",
                                MAVEN_MODEL + "Model").and(isStatic()),
                        Advices.ProjectName.class),
                new Hook("getWorkspaceArtifact", M2E + "registry.EclipseWorkspaceArtifactRepository",
                        "getWorkspaceArtifact(String, String, String)",
                        signature("getWorkspaceArtifact", "java.lang.String", "java.lang.String", "java.lang.String")
                                .and(not(isStatic())),
                        Advices.WorkspaceArtifact.class),
                new Hook("getProjectFacade", M2E + "registry.BasicProjectRegistry",
                        "getProjectFacade(String, String, String)",
                        signature("getProjectFacade", "java.lang.String", "java.lang.String", "java.lang.String")
                                .and(not(isStatic())),
                        Advices.ProjectFacade.class),
                new Hook("populateClasspath", "org.eclipse.m2e.jdt.internal.DefaultClasspathManagerDelegate",
                        "populateClasspath(IClasspathDescriptor, IMavenProjectFacade, int, IProgressMonitor)",
                        signature("populateClasspath", "org.eclipse.m2e.jdt.IClasspathDescriptor",
                                "org.eclipse.m2e.core.project.IMavenProjectFacade", "int",
                                "org.eclipse.core.runtime.IProgressMonitor"),
                        Advices.PopulateClasspath.class),
                new Hook("readParentExternally", MAVEN_MODEL + "building.DefaultModelBuilder",
                        "readParentExternally(Model, ModelBuildingRequest, DefaultModelProblemCollector)",
                        signature("readParentExternally", MAVEN_MODEL + "Model",
                                MAVEN_MODEL + "building.ModelBuildingRequest",
                                MAVEN_MODEL + "building.DefaultModelProblemCollector"),
                        Advices.ReadParentExternally.class),
                new Hook("initParent", "org.apache.maven.project.DefaultProjectBuilder",
                        "initParent(MavenProject, Map, boolean, ModelBuildingResult, ProjectBuildingRequest)",
                        signature("initParent", "org.apache.maven.project.MavenProject", "java.util.Map", "boolean",
                                MAVEN_MODEL + "building.ModelBuildingResult",
                                "org.apache.maven.project.ProjectBuildingRequest"),
                        Advices.InitParent.class));
    }

    /** A method with this name and exactly these parameter types, by erasure. */
    static ElementMatcher.Junction<MethodDescription> signature(String name, String... parameterTypes) {
        ElementMatcher.Junction<MethodDescription> matcher = named(name);
        matcher = matcher.and(takesArguments(parameterTypes.length));
        for (int i = 0; i < parameterTypes.length; i++) {
            matcher = matcher.and(takesArgument(i, named(parameterTypes[i])));
        }
        return matcher;
    }

    /** This hook with another matcher. For test-only variants of the agent (design §6: the product has no switch). */
    public Hook withMatcher(ElementMatcher<? super MethodDescription> other) {
        return new Hook(id, type, method, other, advice);
    }
}

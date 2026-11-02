package org.fractal.spike.jdtls.agent;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.File;
import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.utility.JavaModule;

import org.fractal.spike.jdtls.boot.Spike;

/**
 * Instruments m2e inside JDT LS. Advice is inlined into m2e classes and only calls {@link Spike},
 * which the manifest's Boot-Class-Path puts on the bootstrap class path.
 */
public final class SpikeAgent {

    private static final String M2E = "org.eclipse.m2e.core.internal.project.";

    private SpikeAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        Spike.started("jdtls-worktree-spike");
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module,
                            boolean loaded, DynamicType dynamicType) {
                        Spike.transformed(type.getName());
                    }

                    @Override
                    public void onError(String type, ClassLoader loader, JavaModule module, boolean loaded, Throwable t) {
                        Spike.transformError(type, t);
                    }
                })
                .type(named(M2E + "ProjectConfigurationManager"))
                .transform((b, td, cl, m, pd) -> b
                        .visit(Advice.to(ProjectName.class).on(named("getProjectName").and(isStatic()).and(takesArguments(2))))
                        .visit(Advice.to(Create.class).on(named("create").and(takesArguments(4)))))
                .type(named(M2E + "registry.BasicProjectRegistry"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(ProjectFacadeByGav.class)
                        .on(named("getProjectFacade").and(takesArguments(3)).and(takesArgument(0, String.class)))))
                .type(named(M2E + "registry.EclipseWorkspaceArtifactRepository"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(WorkspaceArtifact.class)
                        .on(named("getWorkspaceArtifact").and(takesArguments(3)))))
                .type(named("org.eclipse.m2e.jdt.internal.DefaultClasspathManagerDelegate"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(PopulateClasspath.class)
                        .on(named("populateClasspath").and(takesArguments(4)))))
                .type(named("org.apache.maven.model.building.DefaultModelBuilder"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(ReadParentExternally.class)
                        .on(named("readParentExternally").and(takesArguments(3)))))
                .type(named("org.apache.maven.project.DefaultProjectBuilder"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(InitParent.class)
                        .on(named("initParent").and(takesArguments(5)))))
                .type(named("org.eclipse.m2e.jdt.internal.ClasspathDescriptor"))
                .transform((b, td, cl, m, pd) -> b
                        .visit(Advice.to(ProjectEntry.class).on(named("addProjectEntry").and(takesArguments(1))))
                        .visit(Advice.to(LibraryEntry.class).on(named("addLibraryEntry").and(takesArguments(1)))))
                .installOn(inst);
    }

    static final class ProjectName {
        @Advice.OnMethodExit
        static void exit(@Advice.Argument(1) Object model, @Advice.Return(readOnly = false) String name) {
            name = Spike.projectName(model, name);
        }
    }

    static final class Create {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Object projectInfo) {
            Spike.createEnter(projectInfo);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Argument(0) Object projectInfo,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object project, @Advice.Thrown Throwable thrown) {
            Spike.createExit(projectInfo, project, thrown);
        }
    }

    static final class ProjectFacadeByGav {
        @Advice.OnMethodExit
        static void exit(@Advice.This Object registry, @Advice.Argument(0) String g, @Advice.Argument(1) String a,
                @Advice.Argument(2) String v,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object facade) {
            facade = Spike.projectFacade(registry, g, a, v, facade);
        }
    }

    static final class WorkspaceArtifact {
        @Advice.OnMethodExit
        static void exit(@Advice.FieldValue("context") Object context, @Advice.Argument(0) String g,
                @Advice.Argument(1) String a, @Advice.Argument(2) String v,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object pom) {
            pom = Spike.workspaceArtifact(context, g, a, v, pom);
        }
    }

    static final class PopulateClasspath {
        @Advice.OnMethodEnter
        static File enter(@Advice.Argument(1) Object facade) {
            return Spike.requesterEnter(facade, "populateClasspath");
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter File previous) {
            Spike.requesterExit(previous, "populateClasspath");
        }
    }

    static final class ReadParentExternally {
        @Advice.OnMethodEnter
        static File enter(@Advice.Argument(0) Object childModel) {
            return Spike.requesterEnterModel(childModel, "readParentExternally");
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter File previous) {
            Spike.requesterExit(previous, "readParentExternally");
        }
    }

    static final class InitParent {
        @Advice.OnMethodEnter
        static File enter(@Advice.Argument(0) Object childProject) {
            return Spike.requesterEnterModel(childProject, "initParent");
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter File previous) {
            Spike.requesterExit(previous, "initParent");
        }
    }

    static final class ProjectEntry {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Object path) {
            Spike.classpathEntry("project", path);
        }
    }

    static final class LibraryEntry {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Object path) {
            Spike.classpathEntry("library", path);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.agent;

import java.io.File;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import org.fractal.jdtls.boot.Boot;

/**
 * The advice for each hook in {@link Hook#all()}. It's inlined into m2e and Maven classes, so it
 * only calls {@link Boot}, which is on the boot class path and visible to every bundle through
 * {@code org.osgi.framework.bootdelegation}. It uses JDK types only.
 */
final class Advices {

    private Advices() {}

    /** Naming: {@code ProjectConfigurationManager.getProjectName(ProjectImportConfiguration, Model)}. */
    static final class ProjectName {
        private ProjectName() {}

        @Advice.OnMethodExit
        static void exit(@Advice.Argument(1) Object model, @Advice.Return(readOnly = false) String name) {
            name = Boot.projectName(model, name);
        }
    }

    /** Resolution: {@code EclipseWorkspaceArtifactRepository.getWorkspaceArtifact(String, String, String)}. */
    static final class WorkspaceArtifact {
        private WorkspaceArtifact() {}

        @Advice.OnMethodExit
        static void exit(@Advice.FieldValue("context") Object context, @Advice.Argument(0) String g,
                @Advice.Argument(1) String a, @Advice.Argument(2) String v,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object pom) {
            pom = Boot.workspaceArtifact(context, g, a, v, pom);
        }
    }

    /** Resolution: {@code BasicProjectRegistry.getProjectFacade(String, String, String)}. */
    static final class ProjectFacade {
        private ProjectFacade() {}

        @Advice.OnMethodExit
        static void exit(@Advice.This Object registry, @Advice.Argument(0) String g, @Advice.Argument(1) String a,
                @Advice.Argument(2) String v,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object facade) {
            facade = Boot.projectFacade(registry, g, a, v, facade);
        }
    }

    /** Requester: {@code DefaultClasspathManagerDelegate.populateClasspath(…, facade, …)}; its first return is the checkpoint. */
    static final class PopulateClasspath {
        private PopulateClasspath() {}

        @Advice.OnMethodEnter
        static File enter(@Advice.Argument(1) Object facade) {
            return Boot.populateClasspathEnter(facade);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter File previous) {
            Boot.populateClasspathExit(previous);
        }
    }

    /** Requester: Maven's {@code DefaultModelBuilder.readParentExternally(childModel, …)}. */
    static final class ReadParentExternally {
        private ReadParentExternally() {}

        @Advice.OnMethodEnter
        static File enter(@Advice.Argument(0) Object childModel) {
            return Boot.readParentExternallyEnter(childModel);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter File previous) {
            Boot.requesterExit(previous);
        }
    }

    /** Requester: Maven's {@code DefaultProjectBuilder.initParent(childProject, …)}. */
    static final class InitParent {
        private InitParent() {}

        @Advice.OnMethodEnter
        static File enter(@Advice.Argument(0) Object childProject) {
            return Boot.initParentEnter(childProject);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter File previous) {
            Boot.requesterExit(previous);
        }
    }
}

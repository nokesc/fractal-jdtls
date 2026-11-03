// SPDX-License-Identifier: Apache-2.0
package org.eclipse.m2e.core.embedder;

/**
 * Test stand-in for m2e's {@code ArtifactKey} record, which the helper constructs by name to
 * look up {@code BasicProjectRegistry.workspaceArtifacts}. Same name and canonical constructor.
 */
public record ArtifactKey(String groupId, String artifactId, String version, String classifier) {}

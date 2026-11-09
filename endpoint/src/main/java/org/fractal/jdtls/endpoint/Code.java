// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

/**
 * The error codes the endpoint returns (design §11 "Errors"). Each must be in the description's catalogue, which
 * gives its status, title and whether it's retryable; startup and a JUnit test check that. {@link #hint} is the
 * default "what to do next", which a problem may replace with a more specific one.
 */
enum Code {
    INVALID_REQUEST("invalid-request", "Fix the fields `errors` names, then send the request again."),
    REQUEST_TOO_LARGE("request-too-large", "Send less."),
    UNAUTHORIZED("unauthorized", "Use the token the endpoint was started with. It isn't something an LLM can fix: report it."),
    HOST_NOT_ALLOWED("host-not-allowed", "Connect to `127.0.0.1` with the port from the port file, and send that as `Host`."),
    ORIGIN_NOT_ALLOWED("origin-not-allowed", "Call the endpoint from a program, not a browser, and send no `Origin` header."),
    ROUTE_NOT_FOUND("route-not-found", "Use a path from this description: `GET /v1/openapi.json` lists them all."),
    METHOD_NOT_ALLOWED("method-not-allowed", "Send the request with the method this description gives for the path."),
    WORKTREE_NOT_REGISTERED("worktree-not-registered", "Add it with `POST /v1/worktrees/add`, or use one of the candidates."),
    WORKTREE_DELETED("worktree-deleted", "Remove it with `POST /v1/worktrees/remove`. If it was moved, add it again at its new path."),
    MODULE_NOT_FOUND("module-not-found", "Use one of the candidates, or `.` for the root POM."),
    MODULE_NOT_IMPORTED("module-not-imported", "Run `POST /v1/build` on the worktree's root module (`.`), so the endpoint takes in the changed POMs."
            + " If it's still missing, remove the worktree and add it again."),
    MODULE_IN_OTHER_WORKTREE("module-in-other-worktree", "Remove the stray `.git` named in `detail`, then remove the worktree and add it again."
            + " Don't work around it: results would come from the wrong worktree."),
    IMPORT_FAILED("import-failed", "Remove the worktree and add it again. If it fails again, report it with the `modules` list:"
            + " it's a JDT LS problem the endpoint couldn't repair."),
    NOT_A_WORKTREE("not-a-worktree", "Pass the absolute path of the worktree's root, the folder `git rev-parse --show-toplevel` prints."),
    WORKTREE_NAME_IN_USE("worktree-name-in-use", "Remove the other worktree first, or use a worktree whose folder has another name."
            + " This is the harness's rule to keep."),
    WORKTREE_NESTED("worktree-nested", "Use worktrees that sit side by side, not one inside another, such as workmux's"
            + " `<repo>__worktrees/` folders. If you mean to replace the registered one, remove it first."),
    INVALID_NAME("invalid-name", "Write the name as the `JavaName` schema describes, for example `com.acme.Foo#bar(int)`."),
    FILE_NOT_FOUND("file-not-found", "Check the path. It's relative to the module, such as `src/main/java/com/acme/Foo.java`."),
    FILE_IN_OTHER_MODULE("file-in-other-module", "Apply the candidate: it sets `module` and the file's path together."),
    NOT_JAVA_SOURCE("not-java-source", "Name a Java file under one of the module's source folders, such as `src/main/java`."),
    TYPE_NOT_FOUND("type-not-found", "Check the package, or use a candidate. A library type can be named only in read-only operations;"
            + " to change code that uses it, address that code."),
    MEMBER_NOT_FOUND("member-not-found", "Use one of the candidates. Members inherited from a supertype are named on the supertype."),
    PACKAGE_NOT_FOUND("package-not-found", "Check the name. A package is named without a `#`, such as `com.acme`."),
    AMBIGUOUS_MEMBER("ambiguous-member", "Choose the candidate you mean and send the request again with it."),
    AMBIGUOUS_TYPE("ambiguous-type", "Choose the module whose type you mean."),
    TEXT_NOT_FOUND("text-not-found", "Read the file again and copy the text exactly, or apply a candidate."),
    TEXT_NOT_UNIQUE("text-not-unique", "Apply a candidate, or make `text` longer, with enough of the surrounding code to occur once."),
    NOT_APPLICABLE("not-applicable", "Address an element of the kind `detail` names."),
    AMBIGUOUS_IMPORT("ambiguous-import", "Apply one candidate for each simple name listed, then send the request again."),
    DECLARED_IN_OTHER_MODULE("declared-in-other-module", "Apply the candidate and send the request again."),
    REFACTORING_REFUSED("refactoring-refused", "Change the request as JDT's reason says, such as another name."),
    EDIT_OUTSIDE_WORKTREE("edit-outside-worktree", "If a symbolic link in the worktree leads out of it, edit through the file it links to, in its own worktree;"
            + " otherwise report it: this shouldn't happen when every module of the worktree is imported."),
    EDIT_CHANGED("edit-changed", "Review `diff`, the edit as it is now; to apply it, send the request again with `expectWriteSetDigest` set to"
            + " this `writeSetDigest`."),
    FILES_CHANGED("files-changed", "Send the same request again: the endpoint computes the edit afresh from the files as they are now."),
    WRITE_FAILED("write-failed", "Check the file's permissions and the disk (`detail` has the system's error), then send the same request again."
            + " Every file the edit had written was put back, unless `detail` says otherwise."),
    TIMEOUT("timeout", "Send it again with a larger `timeoutMs`, or narrow it (a smaller `limit`, the module scope)."),
    BUSY("busy", "Wait `retryAfter` seconds and send the same request again."),
    NOT_READY("not-ready", "Wait `retryAfter` seconds and send the same request again, or call `GET /v1/status?wait=ready` first."),
    SHUTTING_DOWN("shutting-down", "Start a new JDT LS: that's the harness's job. Don't retry against this one."),
    LSP_ERROR("lsp-error", "Read `lsp.message`. Check the method's params against the LSP specification: positions are 0-based there."),
    LSP_METHOD_REFUSED("lsp-method-refused", "Use the endpoint's own operation instead: `worktrees/add` and `/remove` for folders."
            + " Files are read from disk, so there's nothing to synchronize."),
    URI_OUTSIDE_WORKTREE("uri-outside-worktree", "Send the request for the worktree that holds the file: apply the candidate."
            + " The endpoint reconciles only the named worktree with the disk, so a file elsewhere could be read stale."),
    INTERNAL("internal","Report it with `instance`. The same request may fail the same way.");

    final String id;
    final String hint;

    Code(String id, String hint) {
        this.id = id;
        this.hint = hint;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractConstantRefactoring;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractTempRefactoring;
import org.eclipse.jdt.internal.corext.refactoring.code.InlineTempRefactoring;
import org.eclipse.jdt.ls.core.internal.ChangeUtil;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.rename.RenameSupport;
import org.eclipse.jdt.ls.core.internal.handlers.OrganizeImportsHandler;
import org.eclipse.jdt.ls.core.internal.text.correction.SourceAssistProcessor;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.CreateChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.eclipse.text.edits.TextEdit;

/**
 * Each T002 refactoring's edit (design §11 "Refactorings and edits"), computed by JDT LS's own engines under the
 * worktree's read lock, after the fixed point, as a {@code WorkspaceEdit} the endpoint applies ({@link Edits}).
 * Every choice JDT LS would ask for is a request field, and every refusal quotes JDT's reason; nothing is ever
 * half done.
 *
 * <ul>
 *   <li><b>Rename:</b> JDT LS's {@code RenameSupport}, with the flags its {@code textDocument/rename} handler uses (the
 *       references, and a field's getter and setter), and {@code ChangeUtil}'s conversion to a {@code WorkspaceEdit}
 *       with file operations. Called directly rather than through the handler, because the handler lets a rename
 *       whose conditions JDT rates an error through (it refuses only fatal ones): a name clash would be written.
 *   <li><b>Organize imports:</b> JDT LS's {@code OrganizeImportsHandler}, with a chooser that answers from the
 *       request's {@code choices}, so it never asks; a name with no choice is {@code ambiguous-import}.
 *   <li><b>Extract local variable, extract constant, extract method, inline local variable:</b> JDT's LTK
 *       refactorings ({@code ExtractTempRefactoring}, {@code ExtractConstantRefactoring},
 *       {@code ExtractMethodRefactoring}, {@code InlineTempRefactoring}), the engines of JDT LS's
 *       {@code java/getRefactorEdit}, called directly: the handler names the new variable, constant or method itself
 *       (a method {@code extracted}) and checks only the initial conditions, without saying why it refused. Here the
 *       requested name is set before the change is made, so it arrives in the one edit, checked against one
 *       baseline (HTTP-FINDINGS proposal 12).
 * </ul>
 */
final class Refactorings {

    /** JDT LS's rename flags: update references, and a renamed field's getter and setter ({@code RenameHandler}). */
    static final int RENAME_FLAGS = RenameSupport.UPDATE_REFERENCES | RenameSupport.UPDATE_GETTER_METHOD | RenameSupport.UPDATE_SETTER_METHOD;

    private Refactorings() {}

    private interface Work {
        Edits run(Targets.Context ctx) throws Exception;
    }

    /** Runs the computation with the request's deadline: a cancelled JDT operation is {@code timeout}. */
    private static Edits compute(CodeRequest.Scope scope, Work work) throws Exception {
        Targets.Context ctx = new Targets.Context(scope);
        try {
            return work.run(ctx);
        } catch (OperationCanceledException e) {
            throw new DiskSync.Cancelled("running", "computing the edit for " + scope.root());
        }
    }

    // --- rename ----------------------------------------------------------------------------------

    static Edits rename(CodeRequest.Scope scope, DiskSync sync, NavOps.Target target, String newName, EditOps.After after) throws Exception {
        return compute(scope, ctx -> {
            Targets.Target t = Targets.resolve(target.name(), target.anchor(), ctx, false);
            IJavaElement e = t.element();
            if (t.library()) {
                throw Problem.of(Code.NOT_APPLICABLE).detail(Locations.name(e) + " is in a library (" + Locations.libraryName(e)
                        + "), not the worktree's sources; only the worktree's own code can be renamed.").with("field", "/target");
            }
            e = declared(e, target, ctx);
            RenameSupport support = RenameSupport.create(e, newName, RENAME_FLAGS);
            if (support == null) {
                throw Problem.of(Code.NOT_APPLICABLE).detail(Locations.name(e) + " is a " + Locations.kind(e)
                        + "; rename takes a type, method, field, local variable, parameter, type parameter or package.").with("field", "/target");
            }
            // No preCheck(): it's for a rename made from a descriptor, and NPEs for one made from an element, which
            // JDT LS's handler never calls it for either. The conditions below include its checks.
            RenameRefactoring refactoring = support.getRenameRefactoring();
            CheckConditionsOperation check = new CheckConditionsOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS);
            CreateChangeOperation create = new CreateChangeOperation(check, RefactoringStatus.ERROR);
            create.run(ctx.monitor);
            RefactoringStatus status = check.getStatus();
            if (status.hasError()) {
                throw refused("rename", messages(status, RefactoringStatus.ERROR), ctx);
            }
            return edits(create.getChange(), ctx, sync, status, after);
        });
    }

    /**
     * The element a rename starts from, which must be declared in the requesting module (design §11 "The contract":
     * a rename covers that module and the modules that depend on it), else {@code declared-in-other-module}. A
     * package is the requesting module's fragment of it.
     */
    private static IJavaElement declared(IJavaElement e, NavOps.Target target, Targets.Context ctx) throws Problem, CoreException {
        if (e instanceof IPackageFragment fragment) {
            List<IPackageFragment> all = Targets.sourcePackages(fragment.getElementName(), ctx);
            for (IPackageFragment f : all) {
                if (f.getJavaProject().getProject().equals(ctx.project)) {
                    return f;
                }
            }
            JsonArray candidates = new JsonArray();
            Set<String> modules = new LinkedHashSet<>();
            for (IPackageFragment f : all) {
                modules.add(Workspace.relative(ctx.root, Targets.Context.location(f.getJavaProject().getProject())));
            }
            for (String m : modules) {
                candidates.add(Mapping.candidate("/module", m, "a module with sources in " + fragment.getElementName()));
            }
            throw Problem.of(Code.DECLARED_IN_OTHER_MODULE)
                    .detail("The package " + fragment.getElementName() + " has no sources in " + ctx.module + "; " + String.join(" and ", modules)
                            + (modules.size() == 1 ? " has." : " have."))
                    .with("field", "/module").with("candidates", candidates);
        }
        ICompilationUnit unit = (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT);
        Path file = unit == null ? null : ctx.path(unit);
        String module = file == null ? null : ctx.moduleOf(file);
        if (module == null) {
            throw new IllegalStateException(Locations.name(e) + " has no source file in " + ctx.root);
        }
        if (!module.equals(ctx.module)) {
            JsonObject set = new JsonObject();
            set.addProperty("/module", module);
            String name = Locations.name(e);
            if (target.anchor() != null && !(e instanceof ILocalVariable)) {
                set.addProperty("/target", name); // the anchor's file is relative to this module: name the element instead
            }
            JsonObject candidate = new JsonObject();
            candidate.add("set", set);
            candidate.addProperty("about", "the declaring module: " + ctx.root.relativize(file));
            JsonArray candidates = new JsonArray();
            candidates.add(candidate);
            throw Problem.of(Code.DECLARED_IN_OTHER_MODULE).detail(name + " is declared in " + module + ", not " + ctx.module + ".")
                    .with("field", "/module").with("candidates", candidates);
        }
        return e;
    }

    // --- organize imports ------------------------------------------------------------------------

    static Edits organizeImports(CodeRequest.Scope scope, DiskSync sync, String file, Map<String, String> choices, EditOps.After after) throws Exception {
        return compute(scope, ctx -> {
            ICompilationUnit unit = Targets.unit(file, ctx, "/file");
            Map<String, List<String>> open = new TreeMap<>();
            Map<String, String> wrong = new TreeMap<>();
            Function<OrganizeImportsHandler.ImportSelection[], OrganizeImportsHandler.ImportCandidate[]> chooser = selections -> {
                List<OrganizeImportsHandler.ImportCandidate> chosen = new ArrayList<>();
                for (OrganizeImportsHandler.ImportSelection s : selections) {
                    if (s.candidates == null || s.candidates.length == 0) {
                        continue;
                    }
                    String simple = EditOps.simpleName(s.candidates[0].fullyQualifiedName);
                    String wanted = choices.get(simple);
                    OrganizeImportsHandler.ImportCandidate match = wanted == null ? null
                            : Arrays.stream(s.candidates).filter(c -> wanted.equals(c.fullyQualifiedName)).findFirst().orElse(null);
                    if (match == null) {
                        open.put(simple, Arrays.stream(s.candidates).map(c -> c.fullyQualifiedName).sorted().toList());
                        if (wanted != null) {
                            wrong.put(simple, wanted);
                        }
                    } else {
                        chosen.add(match);
                    }
                }
                return open.isEmpty() ? chosen.toArray(OrganizeImportsHandler.ImportCandidate[]::new) : null;
            };
            TextEdit edit = OrganizeImportsHandler.organizeImports(unit, chooser);
            if (!wrong.isEmpty()) {
                Problem invalid = Problem.of(Code.INVALID_REQUEST).detail(wrong.size() == 1 ? "1 field is invalid." : wrong.size() + " fields are invalid.");
                wrong.forEach((simple, wanted) -> invalid.error("/choices/" + simple,
                        "'" + HttpApi.printable(wanted) + "' isn't a type " + simple + " can be imported from here; it can be " + String.join(" or ", open.get(simple))));
                throw invalid;
            }
            if (!open.isEmpty()) {
                throw ambiguousImport(open, ctx);
            }
            WorkspaceEdit workspaceEdit = edit == null ? new WorkspaceEdit() : SourceAssistProcessor.convertToWorkspaceEdit(unit, edit);
            return Edits.of(workspaceEdit, ctx, sync, List.of(), after.severities(), after.limit());
        });
    }

    private static Problem ambiguousImport(Map<String, List<String>> open, Targets.Context ctx) throws CoreException {
        JsonArray candidates = new JsonArray();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : open.entrySet()) {
            parts.add(e.getKey() + " could be imported from " + e.getValue().size() + " types (" + String.join(", ", e.getValue()) + ")");
            for (String type : e.getValue()) {
                IType found = ctx.java == null ? null : ctx.java.findType(type, ctx.monitor);
                String about = found == null ? type : Locations.kind(found) + " " + type
                        + (Locations.library(found, ctx.root) ? ", from " + Locations.libraryName(found) : ", in this worktree");
                candidates.add(Mapping.candidate("/choices/" + e.getKey(), type, about));
            }
        }
        return Problem.of(Code.AMBIGUOUS_IMPORT).detail(String.join("; ", parts) + ".")
                .with("field", "/choices/" + open.keySet().iterator().next()).with("candidates", candidates);
    }

    // --- extract and inline ----------------------------------------------------------------------

    static Edits extractVariable(CodeRequest.Scope scope, DiskSync sync, NavOps.Anchor anchor, String name, boolean replaceAll, EditOps.After after)
            throws Exception {
        return compute(scope, ctx -> {
            Targets.Range r = Targets.range(anchor, ctx);
            ExtractTempRefactoring x = new ExtractTempRefactoring(r.unit(), r.start(), r.end() - r.start());
            x.setReplaceAllOccurrences(replaceAll);
            x.setDeclareFinal(declareFinal());
            RefactoringStatus status = initial("extract-variable", x, r, ctx);
            x.setTempName(name);
            return finish("extract-variable", x, status, x.checkTempName(name), ctx, sync, after);
        });
    }

    static Edits extractConstant(CodeRequest.Scope scope, DiskSync sync, NavOps.Anchor anchor, String name, boolean replaceAll, EditOps.After after)
            throws Exception {
        return compute(scope, ctx -> {
            Targets.Range r = Targets.range(anchor, ctx);
            ExtractConstantRefactoring x = new ExtractConstantRefactoring(r.unit(), r.start(), r.end() - r.start());
            x.setReplaceAllOccurrences(replaceAll);
            x.setVisibility("private");
            x.setQualifyReferencesWithDeclaringClassName(false);
            RefactoringStatus status = initial("extract-constant", x, r, ctx);
            x.setConstantName(name);
            return finish("extract-constant", x, status, x.checkConstantNameOnChange(), ctx, sync, after);
        });
    }

    static Edits extractMethod(CodeRequest.Scope scope, DiskSync sync, NavOps.Anchor anchor, String name, EditOps.After after) throws Exception {
        return compute(scope, ctx -> {
            Targets.Range r = Targets.range(anchor, ctx);
            ExtractMethodRefactoring x = new ExtractMethodRefactoring(r.unit(), r.start(), r.end() - r.start());
            RefactoringStatus status = initial("extract-method", x, r, ctx);
            x.setMethodName(name);
            x.setVisibility(Modifier.PRIVATE);
            RefactoringStatus named = x.checkMethodName();
            named.merge(x.checkParameterNames());
            return finish("extract-method", x, status, named, ctx, sync, after);
        });
    }

    static Edits inlineVariable(CodeRequest.Scope scope, DiskSync sync, NavOps.Anchor anchor, EditOps.After after) throws Exception {
        return compute(scope, ctx -> {
            Targets.Target t = Targets.anchor(anchor, ctx);
            if (!(t.element() instanceof ILocalVariable local) || local.isParameter()) {
                String what = t.element() instanceof ILocalVariable ? "a parameter" : "a " + Locations.kind(t.element());
                throw Problem.of(Code.NOT_APPLICABLE)
                        .detail(Locations.name(t.element()) + " is " + what + "; inline-variable takes a local variable, at its declaration or a use.")
                        .with("field", anchor.select() != null ? "/target/select" : "/target/text");
            }
            InlineTempRefactoring x = new InlineTempRefactoring(t.unit(), t.offset(), t.length());
            RefactoringStatus status = x.checkInitialConditions(ctx.monitor);
            if (status.hasError()) {
                throw refused("inline-variable", messages(status, RefactoringStatus.ERROR), ctx);
            }
            return finish("inline-variable", x, status, new RefactoringStatus(), ctx, sync, after);
        });
    }

    /** The initial conditions, which are about the selection: an error there is {@code not-applicable}, in JDT's words. */
    private static RefactoringStatus initial(String operation, Refactoring x, Targets.Range r, Targets.Context ctx) throws Problem, CoreException {
        RefactoringStatus status = x.checkInitialConditions(ctx.monitor);
        if (status.hasError()) {
            String selected = HttpApi.printable(r.text().text().substring(r.start(), r.end()));
            throw Problem.of(Code.NOT_APPLICABLE)
                    .detail("JDT can't " + operation.replace('-', ' ') + " '" + selected + "' at line " + r.text().lineOf(r.start()) + ": \""
                            + sentences(messages(status, RefactoringStatus.ERROR).stream().map(m -> inCallerTerms(m, ctx)).toList()) + "\"")
                    .hint("Select exactly what to extract: a whole expression, or whole statements from the start of the first to the end of the last."
                            + " Use `select` to pick it inside `text`.")
                    .with("field", "/target");
        }
        return status;
    }

    /** The name's check and the final conditions; an error is {@code refactoring-refused}. Then the edit. */
    private static Edits finish(String operation, Refactoring x, RefactoringStatus status, RefactoringStatus named, Targets.Context ctx, DiskSync sync,
            EditOps.After after) throws Exception {
        status.merge(named);
        if (!named.hasError()) {
            status.merge(x.checkFinalConditions(ctx.monitor));
        }
        if (status.hasError()) {
            throw refused(operation, messages(status, RefactoringStatus.ERROR), ctx);
        }
        return edits(x.createChange(ctx.monitor), ctx, sync, status, after);
    }

    private static Edits edits(Change change, Targets.Context ctx, DiskSync sync, RefactoringStatus status, EditOps.After after) throws Exception {
        if (change == null) {
            throw new IllegalStateException("JDT made no change, and gave no reason");
        }
        try {
            WorkspaceEdit edit = ChangeUtil.convertToWorkspaceEdit(change);
            List<String> warnings = messages(status, RefactoringStatus.WARNING).stream().map(m -> inCallerTerms(m, ctx)).toList();
            return Edits.of(edit, ctx, sync, warnings, after.severities(), after.limit());
        } finally {
            change.dispose();
        }
    }

    /** Whether new declarations are {@code final}, as JDT LS's {@code java.codeGeneration.addFinalForNewDeclaration} says. */
    private static boolean declareFinal() {
        String setting = JavaLanguageServerPlugin.getPreferencesManager().getPreferences().getCodeGenerationAddFinalForNewDeclaration();
        return "all".equals(setting) || "variables".equals(setting);
    }

    private static Problem refused(String operation, List<String> messages, Targets.Context ctx) {
        String reason = sentences(messages.stream().map(m -> inCallerTerms(m, ctx)).toList());
        return Problem.of(Code.REFACTORING_REFUSED).detail("JDT refused the " + operation.replace('-', ' ') + ": \"" + reason + "\" Nothing was written.");
    }

    /** The status's messages of at least {@code severity}, at most exactly that one for warnings. */
    static List<String> messages(RefactoringStatus status, int severity) {
        List<String> list = new ArrayList<>();
        for (RefactoringStatusEntry e : status.getEntries()) {
            boolean wanted = severity == RefactoringStatus.WARNING ? e.getSeverity() == RefactoringStatus.WARNING : e.getSeverity() >= severity;
            if (wanted && e.getMessage() != null && !list.contains(e.getMessage())) {
                list.add(e.getMessage());
            }
        }
        return list;
    }

    /** JDT's messages as one text: each ends with its own full stop, as JDT leaves some without. */
    static String sentences(List<String> messages) {
        StringBuilder b = new StringBuilder();
        for (String m : messages) {
            String t = m.strip();
            if (t.isEmpty()) {
                continue;
            }
            b.append(b.isEmpty() ? "" : " ").append(t);
            if (!t.endsWith(".") && !t.endsWith("!") && !t.endsWith("?")) {
                b.append('.');
            }
        }
        return b.toString();
    }

    /** JDT's message in the caller's terms: a resource path's Eclipse project name becomes its module's folder. */
    static String inCallerTerms(String message, Targets.Context ctx) {
        String m = message;
        for (IProject p : ctx.projects) {
            Path location = Targets.Context.location(p);
            if (location != null) {
                String module = Workspace.relative(ctx.root, location);
                m = m.replace("/" + p.getName() + "/", module.equals(".") ? "" : module + "/");
            }
        }
        return m;
    }
}

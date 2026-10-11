/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evm.loopgen;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

/**
 * Copies the methods of the operation classes marked {@code @InlineInEvmLoop} into the switch of
 * the untraced EVM v2 loop in EVM.java, one case each, and checks those methods against the rules
 * that keep the loop fast. The rules and why each exists are documented on {@code InlineInEvmLoop}.
 *
 * <p>Usage: {@code EvmV2LoopGenerator write|check <operations dir> <EVM.java> [<marker file>]}.
 * {@code write} rewrites the generated regions of EVM.java; {@code check} fails if any rule is
 * broken or EVM.java does not hold what the methods generate, and on success touches the marker
 * file.
 */
public final class EvmV2LoopGenerator {

  static final String TABLE_BEGIN = "// BEGIN GENERATED case table";
  static final String TABLE_END = "// END GENERATED case table";
  static final String CASES_BEGIN = "// BEGIN GENERATED cases";
  static final String CASES_END = "// END GENERATED cases";

  /** Where the constants and helpers an inline method may name come from, for it and EVM.java. */
  static final String INLINING_CLASS = "org.hyperledger.besu.evm.v2.operation.EvmLoopInlining";

  /** The loop state an inline method may take as a parameter, by name, with its type. */
  private static final Map<String, String> CONTEXT = new LinkedHashMap<>();

  static {
    CONTEXT.put("s", "long[]");
    CONTEXT.put("sp", "int");
    CONTEXT.put("top", "int");
    CONTEXT.put("next", "int");
    CONTEXT.put("pc", "int");
    CONTEXT.put("at", "int");
    CONTEXT.put("code", "byte[]");
    CONTEXT.put("codeObject", "Code");
    CONTEXT.put("opcode", "int");
    CONTEXT.put("gas", "long");
    CONTEXT.put("shanghai", "boolean");
    CONTEXT.put("untraced", "boolean");
  }

  /** The locals of the loop that an inline method's own locals must not shadow. */
  private static final Set<String> LOOP_LOCALS =
      Set.of(
          "cost",
          "step",
          "delta",
          "entry",
          "bias",
          "base",
          "pcBase",
          "opcode",
          "s",
          "sp",
          "pc",
          "gas",
          "code",
          "codeObject",
          "frame",
          "shanghai",
          "result");

  /**
   * Constants an inline method may read. Anything else it reads is a parameter or its own local.
   */
  private static final Set<String> CONSTANTS =
      Set.of(
          "BASE_TIER_GAS",
          "VERY_LOW_TIER_GAS",
          "LOW_TIER_GAS",
          "MID_TIER_GAS",
          "HIGH_TIER_GAS",
          "JUMPDEST_GAS",
          "LONG_BE",
          "FALLBACK");

  /**
   * The only calls an inline method may make: intrinsics, the VarHandle for words in byte arrays,
   * and small accessors C2 inlines at any call site. EvmLoopMethodSizeTest checks the accessors
   * stay small.
   */
  private static final Set<String> CALLS =
      Set.of(
          "done",
          "jumped",
          "isJumpDestinationV2",
          "Long.bitCount",
          "Long.compareUnsigned",
          "Long.numberOfLeadingZeros",
          "Long.numberOfTrailingZeros",
          "LONG_BE.get",
          "codeObject.getSize",
          "codeObject.getJumpDestBitMask");

  /** The loop state a helper takes under the loop's own name, which its callers pass as it is. */
  private static final Set<String> PASSED_THROUGH = Set.of("s", "codeObject");

  /** Fields an inline method may read through a qualifier, besides the length of an array. */
  private static final Set<String> FIELDS = Set.of("Long.MIN_VALUE", "Long.MAX_VALUE");

  private EvmV2LoopGenerator() {}

  /** One case of the loop: the method it copies, its constant and the opcodes it runs. */
  private record SwitchCase(
      String owner, String method, String constant, Set<Integer> opcodes, String body) {

    /** The lowest opcode, which decides where the case comes in the loop. */
    int first() {
      return opcodes.stream().min(Integer::compare).orElse(0);
    }
  }

  /**
   * Runs the generator.
   *
   * @param args the mode, the operations directory, EVM.java and, for check, a marker file
   * @throws IOException if a file cannot be read or written
   */
  public static void main(final String[] args) throws IOException {
    if (args.length < 3 || !(args[0].equals("write") || args[0].equals("check"))) {
      System.err.println(
          "usage: EvmV2LoopGenerator write|check <operations dir> <EVM.java> [<marker file>]");
      System.exit(2);
    }
    final Path operations = Path.of(args[1]);
    final Path evmFile = Path.of(args[2]);
    final List<String> errors = new ArrayList<>();
    final Set<String> named = new TreeSet<>();
    final Set<String> copied = new TreeSet<>();
    final List<SwitchCase> cases = parse(operations, named, copied, errors);
    checkNamesResolveToInlining(evmFile, named, copied, errors);
    if (!errors.isEmpty()) {
      System.err.println(
          "The @InlineInEvmLoop methods in "
              + operations
              + " break the rules documented on @InlineInEvmLoop:");
      errors.forEach(e -> System.err.println("  " + e));
      System.exit(1);
    }
    final String current = Files.readString(evmFile, UTF_8);
    final String generated = generate(current, cases);
    if (args[0].equals("write")) {
      Files.writeString(evmFile, generated, UTF_8);
      System.out.println("Wrote " + cases.size() + " cases into " + evmFile);
    } else {
      if (!withoutWhitespace(generated).equals(withoutWhitespace(current))) {
        System.err.println(
            evmFile
                + " does not hold the copies of the @InlineInEvmLoop methods in "
                + operations
                + ". Edit the method in its operation's class, then run ./gradlew"
                + " :evm:generateEvmV2Loop.");
        System.exit(1);
      }
      if (args.length > 3) {
        final Path marker = Path.of(args[3]);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "checked " + cases.size() + " cases\n", UTF_8);
      }
    }
  }

  private static String withoutWhitespace(final String text) {
    final StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (!Character.isWhitespace(c)) {
        out.append(c);
      }
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Parsing and checking

  private static List<SwitchCase> parse(
      final Path operations,
      final Set<String> named,
      final Set<String> copied,
      final List<String> errors)
      throws IOException {
    final List<Path> sources;
    try (Stream<Path> files = Files.list(operations)) {
      sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    }
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    final List<SwitchCase> cases = new ArrayList<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, UTF_8)) {
      final JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  null,
                  List.of("-proc:none"),
                  null,
                  files.getJavaFileObjectsFromPaths(sources));
      final SourcePositions positions = Trees.instance(task).getSourcePositions();
      record Marked(CompilationUnitTree unit, String source, ClassTree owner, MethodTree method) {}
      final List<Marked> marked = new ArrayList<>();
      for (final CompilationUnitTree unit : task.parse()) {
        final String source = unit.getSourceFile().getCharContent(true).toString();
        for (final Tree type : unit.getTypeDecls()) {
          if (!(type instanceof ClassTree classTree)) {
            continue;
          }
          for (final Tree member : classTree.getMembers()) {
            if (member instanceof MethodTree method && inlineAnnotation(method) != null) {
              marked.add(new Marked(unit, source, classTree, method));
            }
          }
        }
      }
      // the helpers first, which the cases that call them copy
      final Map<String, InlineHelper> helpers = new LinkedHashMap<>();
      for (final Marked m : marked) {
        if (inlineAnnotation(m.method()).getArguments().isEmpty()) {
          final InlineHelper helper =
              new InlineHelper(m.unit(), positions, m.source(), m.owner(), m.method(), errors);
          if (helpers.put(helper.name, helper) != null) {
            errors.add("two helpers copied into the loop are called " + helper.name);
          }
          copied.add(helper.name);
          named.addAll(helper.named);
        }
      }
      for (final Marked m : marked) {
        if (!inlineAnnotation(m.method()).getArguments().isEmpty()) {
          final InlineMethod inline =
              new InlineMethod(
                  m.unit(), positions, m.source(), m.owner(), m.method(), helpers, errors);
          cases.add(inline.toSwitchCase());
          named.addAll(inline.named);
          checkNamesResolveToInlining(
              m.unit(), m.owner(), inline.named, m.owner().getSimpleName().toString(), errors);
        }
      }
    }
    if (cases.isEmpty()) {
      errors.add("no @InlineInEvmLoop methods found in " + operations);
    }
    cases.sort(Comparator.comparingInt(SwitchCase::first));
    final Map<Integer, String> owners = new HashMap<>();
    final Map<String, String> constants = new HashMap<>();
    for (final SwitchCase switchCase : cases) {
      final String where = switchCase.owner() + "." + switchCase.method();
      final String other = constants.put(switchCase.constant(), where);
      if (other != null) {
        errors.add(
            where
                + " and "
                + other
                + " need names of their own, as the loop names its cases after them");
      }
      for (final int opcode : switchCase.opcodes()) {
        final String owner = owners.put(opcode, where);
        if (owner != null) {
          errors.add(
              String.format(
                  Locale.ROOT, "opcode 0x%02x belongs to both %s and %s", opcode, owner, where));
        }
      }
    }
    return cases;
  }

  /**
   * An inline method names constants and helpers without a qualifier, in its operation's class and,
   * once generated, inside EVM. Java resolves such a name by where it stands, and a member of the
   * enclosing class hides a static import of the same name, overloads included. So each name has to
   * come from EvmLoopInlining in both places, and neither class may declare a member of that name.
   */
  private static void checkNamesResolveToInlining(
      final Path evmFile,
      final Set<String> named,
      final Set<String> copied,
      final List<String> errors)
      throws IOException {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, UTF_8)) {
      final JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  null,
                  List.of("-proc:none"),
                  null,
                  files.getJavaFileObjects(evmFile));
      final CompilationUnitTree unit = task.parse().iterator().next();
      for (final Tree type : unit.getTypeDecls()) {
        if (type instanceof ClassTree classTree) {
          // the generated code never names done or FALLBACK: it translates them
          final Set<String> used = new TreeSet<>(named);
          used.removeAll(Set.of("done", "jumped", "FALLBACK"));
          // nor the helpers, whose bodies it holds instead
          used.removeAll(copied);
          checkNamesResolveToInlining(unit, classTree, used, evmFile.toString(), errors);
        }
      }
    }
  }

  private static void checkNamesResolveToInlining(
      final CompilationUnitTree unit,
      final ClassTree classTree,
      final Set<String> names,
      final String where,
      final List<String> errors) {
    final Set<String> imported = new HashSet<>();
    for (final ImportTree anImport : unit.getImports()) {
      final String name = anImport.getQualifiedIdentifier().toString();
      if (anImport.isStatic() && name.startsWith(INLINING_CLASS + ".")) {
        imported.add(name.substring(INLINING_CLASS.length() + 1));
      }
    }
    final Set<String> declared = new HashSet<>();
    for (final Tree member : classTree.getMembers()) {
      if (member instanceof MethodTree method) {
        declared.add(method.getName().toString());
      } else if (member instanceof VariableTree variable) {
        declared.add(variable.getName().toString());
      }
    }
    for (final String name : names) {
      if (!imported.contains(name)) {
        errors.add(where + " has to import " + name + " statically from " + INLINING_CLASS);
      }
      if (declared.contains(name)) {
        errors.add(
            where
                + " declares "
                + name
                + ", which hides the one the inline methods mean; give it a name of its own");
      }
    }
  }

  private static AnnotationTree inlineAnnotation(final MethodTree method) {
    for (final AnnotationTree annotation : method.getModifiers().getAnnotations()) {
      if (annotation.getAnnotationType().toString().equals("InlineInEvmLoop")) {
        return annotation;
      }
    }
    return null;
  }

  /** One inline method: its checks and its translation into loop code. */
  private static final class InlineMethod {
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final String source;
    private final ClassTree owner;
    private final MethodTree method;
    private final List<String> errors;
    private final String constant;

    /** The constants and helpers of EvmLoopInlining the method names without a qualifier. */
    final Set<String> named = new TreeSet<>();

    private final Set<String> parameters = new HashSet<>();
    private final Set<String> locals = new HashSet<>();
    private final Map<String, List<ExpressionTree>> assignments = new HashMap<>();
    private final Map<String, InlineHelper> helpers;

    InlineMethod(
        final CompilationUnitTree unit,
        final SourcePositions positions,
        final String source,
        final ClassTree owner,
        final MethodTree method,
        final Map<String, InlineHelper> helpers,
        final List<String> errors) {
      this.unit = unit;
      this.positions = positions;
      this.source = source;
      this.owner = owner;
      this.method = method;
      this.helpers = helpers;
      this.errors = errors;
      this.constant = "INLINE_" + upperSnake(method.getName().toString());
    }

    SwitchCase toSwitchCase() {
      final Set<Integer> opcodes = opcodes();
      checkSignature();
      collectLocals();
      checkBody();
      return new SwitchCase(
          owner.getSimpleName().toString(),
          method.getName().toString(),
          constant,
          opcodes,
          translate());
    }

    private void error(final Tree tree, final String message) {
      final LineMap lines = unit.getLineMap();
      final long line = lines.getLineNumber(positions.getStartPosition(unit, tree));
      errors.add(
          owner.getSimpleName() + "." + method.getName() + " (line " + line + "): " + message);
    }

    private Set<Integer> opcodes() {
      final Set<Integer> opcodes = new TreeSet<>();
      int first = -1;
      int last = -1;
      for (final ExpressionTree argument : inlineAnnotation(method).getArguments()) {
        if (!(argument instanceof AssignmentTree assignment)) {
          error(argument, "@InlineInEvmLoop takes named values");
          continue;
        }
        final String name = assignment.getVariable().toString();
        final ExpressionTree value = assignment.getExpression();
        switch (name) {
          case "opcodes" -> {
            if (value instanceof NewArrayTree array) {
              array.getInitializers().forEach(e -> opcodes.add(intValue(e)));
            } else {
              opcodes.add(intValue(value));
            }
          }
          case "first" -> first = intValue(value);
          case "last" -> last = intValue(value);
          default -> error(argument, "unknown @InlineInEvmLoop value " + name);
        }
      }
      if ((first < 0) != (last < 0) || first > last) {
        error(method, "@InlineInEvmLoop needs both first and last, first no greater than last");
      } else if (first >= 0) {
        for (int op = first; op <= last; op++) {
          opcodes.add(op);
        }
      }
      if (opcodes.isEmpty()) {
        error(method, "@InlineInEvmLoop names no opcode");
      }
      opcodes.forEach(
          op -> {
            if (op < 0 || op > 0xff) {
              error(method, "opcode " + op + " is out of range");
            }
          });
      return opcodes;
    }

    private int intValue(final ExpressionTree tree) {
      if (tree instanceof LiteralTree literal && literal.getValue() instanceof Integer value) {
        if (!source
            .substring((int) start(tree), (int) end(tree))
            .toLowerCase(Locale.ROOT)
            .startsWith("0x")) {
          error(tree, String.format(Locale.ROOT, "write opcodes in hex, as 0x%02x", value));
        }
        return value;
      }
      error(tree, "@InlineInEvmLoop opcodes must be int literals");
      return -1;
    }

    private void checkSignature() {
      if (!method.getReturnType().toString().equals("long")
          || !method.getModifiers().getFlags().contains(Modifier.STATIC)) {
        error(method, "an inline method is static and returns long");
      }
      for (final VariableTree parameter : method.getParameters()) {
        final String name = parameter.getName().toString();
        final String expected = CONTEXT.get(name);
        if (expected == null) {
          error(
              parameter,
              name + " is not loop state an inline method can take; see @InlineInEvmLoop");
        } else if (!expected.equals(parameter.getType().toString())) {
          error(parameter, name + " must be a " + expected);
        }
        if (!parameter.getModifiers().getFlags().contains(Modifier.FINAL)) {
          error(parameter, name + " must be final: the loop's state changes only through done()");
        }
        parameters.add(name);
      }
    }

    private void collectLocals() {
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitVariable(final VariableTree variable, final Void unused) {
          final String name = variable.getName().toString();
          if (LOOP_LOCALS.contains(name) || CONTEXT.containsKey(name)) {
            error(variable, "the local " + name + " would shadow a local of the loop");
          }
          locals.add(name);
          if (variable.getInitializer() != null) {
            assignments
                .computeIfAbsent(name, k -> new ArrayList<>())
                .add(variable.getInitializer());
          }
          return super.visitVariable(variable, unused);
        }

        @Override
        public Void visitAssignment(final AssignmentTree assignment, final Void unused) {
          if (assignment.getVariable() instanceof IdentifierTree target) {
            assignments
                .computeIfAbsent(target.getName().toString(), k -> new ArrayList<>())
                .add(assignment.getExpression());
          }
          return super.visitAssignment(assignment, unused);
        }
      }.scan(method.getBody(), null);
    }

    /** The locals whose values are built on one of the given parameters. */
    private Set<String> derivedFrom(final Set<String> roots) {
      final Set<String> derived = new HashSet<>(roots);
      boolean changed = true;
      while (changed) {
        changed = false;
        for (final Map.Entry<String, List<ExpressionTree>> entry : assignments.entrySet()) {
          if (!derived.contains(entry.getKey())
              && entry.getValue().stream().anyMatch(e -> mentionsAny(e, derived))) {
            derived.add(entry.getKey());
            changed = true;
          }
        }
      }
      return derived;
    }

    private static boolean mentionsAny(final Tree tree, final Set<String> names) {
      final boolean[] found = {false};
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitIdentifier(final IdentifierTree identifier, final Void unused) {
          if (names.contains(identifier.getName().toString())) {
            found[0] = true;
          }
          return null;
        }
      }.scan(tree, null);
      return found[0];
    }

    private void checkBody() {
      final Set<String> stackIndices = derivedFrom(Set.of("top", "next"));
      final Set<String> codeIndices = derivedFrom(Set.of("at"));
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitForLoop(final ForLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitForLoop(tree, unused);
        }

        @Override
        public Void visitEnhancedForLoop(final EnhancedForLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitEnhancedForLoop(tree, unused);
        }

        @Override
        public Void visitWhileLoop(final WhileLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitWhileLoop(tree, unused);
        }

        @Override
        public Void visitDoWhileLoop(final DoWhileLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitDoWhileLoop(tree, unused);
        }

        @Override
        public Void visitNewClass(final NewClassTree tree, final Void unused) {
          error(tree, "no allocation: it makes C2 keep the loop's locals in memory");
          return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitNewArray(final NewArrayTree tree, final Void unused) {
          error(tree, "no allocation: it makes C2 keep the loop's locals in memory");
          return super.visitNewArray(tree, unused);
        }

        @Override
        public Void visitLambdaExpression(final LambdaExpressionTree tree, final Void unused) {
          error(tree, "no lambdas: they allocate and call");
          return null;
        }

        @Override
        public Void visitMemberReference(final MemberReferenceTree tree, final Void unused) {
          error(tree, "no method references: they allocate and call");
          return null;
        }

        @Override
        public Void visitTry(final TryTree tree, final Void unused) {
          error(tree, "no try: halts belong to the operation's implementation");
          return super.visitTry(tree, unused);
        }

        @Override
        public Void visitThrow(final ThrowTree tree, final Void unused) {
          error(tree, "no throw: halts belong to the operation's implementation");
          return super.visitThrow(tree, unused);
        }

        @Override
        public Void visitSynchronized(final SynchronizedTree tree, final Void unused) {
          error(tree, "no synchronization");
          return super.visitSynchronized(tree, unused);
        }

        @Override
        public Void visitSwitch(final SwitchTree tree, final Void unused) {
          error(tree, "no switch: a nested table jump costs bytecode and a second indirect jump");
          return super.visitSwitch(tree, unused);
        }

        @Override
        public Void visitSwitchExpression(final SwitchExpressionTree tree, final Void unused) {
          error(tree, "no switch: a nested table jump costs bytecode and a second indirect jump");
          return super.visitSwitchExpression(tree, unused);
        }

        @Override
        public Void visitLiteral(final LiteralTree tree, final Void unused) {
          if (tree.getKind() == Tree.Kind.STRING_LITERAL) {
            error(tree, "no strings: they allocate");
          }
          return null;
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree tree, final Void unused) {
          final String name = tree.getMethodSelect().toString();
          if (tree.getMethodSelect() instanceof IdentifierTree) {
            named.add(name);
          } else if (tree.getMethodSelect() instanceof MemberSelectTree select
              && select.getExpression() instanceof IdentifierTree qualifier
              && CONSTANTS.contains(qualifier.getName().toString())) {
            named.add(qualifier.getName().toString());
          }
          final InlineHelper helper = helpers.get(name);
          if (helper != null) {
            checkHelperCall(tree, helper);
          } else if (!CALLS.contains(name)) {
            error(
                tree,
                "the call "
                    + name
                    + " is not one C2 is sure to inline; a call it does not inline makes it keep "
                    + "the loop's locals in memory");
          } else if (tree.getMethodSelect() instanceof MemberSelectTree select
              && !isClassName(select.getExpression())) {
            scan(select.getExpression(), unused);
          }
          for (final ExpressionTree argument : tree.getArguments()) {
            scan(argument, unused);
          }
          return null;
        }

        @Override
        public Void visitMemberSelect(final MemberSelectTree tree, final Void unused) {
          final String name = tree.toString();
          if (!tree.getIdentifier().contentEquals("length") && !FIELDS.contains(name)) {
            error(tree, "the field " + name + " is not one an inline method may read");
          }
          if (!isClassName(tree.getExpression())) {
            scan(tree.getExpression(), unused);
          }
          return null;
        }

        @Override
        public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
          final String name = tree.getName().toString();
          if (CONSTANTS.contains(name) && !parameters.contains(name) && !locals.contains(name)) {
            named.add(name);
          }
          if (!parameters.contains(name) && !locals.contains(name) && !CONSTANTS.contains(name)) {
            error(
                tree,
                name
                    + " is neither a parameter, a local nor a constant an inline method may read; a field "
                    + "of the EVM is a load C2 moves ahead of the dispatch");
          }
          return null;
        }

        @Override
        public Void visitAssignment(final AssignmentTree tree, final Void unused) {
          checkTarget(tree.getVariable());
          return super.visitAssignment(tree, unused);
        }

        @Override
        public Void visitCompoundAssignment(final CompoundAssignmentTree tree, final Void unused) {
          checkTarget(tree.getVariable());
          return super.visitCompoundAssignment(tree, unused);
        }

        @Override
        public Void visitUnary(final UnaryTree tree, final Void unused) {
          switch (tree.getKind()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT ->
                checkTarget(tree.getExpression());
            default -> {
              // no assignment
            }
          }
          return super.visitUnary(tree, unused);
        }

        private void checkTarget(final ExpressionTree target) {
          if (target instanceof IdentifierTree identifier
              && parameters.contains(identifier.getName().toString())) {
            error(target, "an inline method changes the loop's state only through done()");
          }
        }

        @Override
        public Void visitReturn(final ReturnTree tree, final Void unused) {
          if (!isDone(tree.getExpression()) && !isFallback(tree.getExpression())) {
            error(
                tree,
                "an inline method returns done(cost, step, delta), jumped(cost, step, delta) or"
                    + " FALLBACK, nothing else");
          }
          return super.visitReturn(tree, unused);
        }

        @Override
        public Void visitArrayAccess(final ArrayAccessTree tree, final Void unused) {
          if (tree.getExpression() instanceof IdentifierTree array) {
            final String name = array.getName().toString();
            if (name.equals("s")) {
              checkIndex(tree, stackIndices, "top or next");
            } else if (name.equals("code")) {
              checkIndex(tree, codeIndices, "at");
            }
          }
          return super.visitArrayAccess(tree, unused);
        }

        private void checkHelperCall(final MethodInvocationTree tree, final InlineHelper helper) {
          if (!helper.isExpression()
              && !(getCurrentPath().getParentPath().getLeaf() instanceof ExpressionStatementTree)) {
            error(tree, "a helper copied into the loop is called as a statement of its own");
          }
          final List<? extends ExpressionTree> arguments = tree.getArguments();
          if (helper.isExpression()) {
            for (final ExpressionTree argument : arguments) {
              if (hasSideEffects(argument)) {
                error(
                    argument,
                    "the arguments of "
                        + helper.name
                        + " are copied where it names them, so they have no side effects");
              }
            }
          }
          for (int i = 0; i < helper.parameters.size() && i < arguments.size(); i++) {
            final String parameter = helper.parameters.get(i);
            final ExpressionTree argument = arguments.get(i);
            if (PASSED_THROUGH.contains(parameter)) {
              if (!(argument instanceof IdentifierTree passed)
                  || !passed.getName().contentEquals(parameter)) {
                error(argument, helper.name + " takes the loop's own " + parameter);
              }
            } else if (helper.stackIndexParameters.contains(parameter)
                && (!mentionsAny(argument, stackIndices)
                    || mentionsAny(argument, Set.of("sp", "pc")))) {
              error(
                  argument,
                  "the index "
                      + argument
                      + " must be built on top or next rather than on sp, so that it carries the "
                      + "case's bias; otherwise C2 computes it ahead of the dispatch for every "
                      + "operation");
            }
          }
        }

        private void checkIndex(
            final ArrayAccessTree tree, final Set<String> derived, final String roots) {
          if (!mentionsAny(tree.getIndex(), derived)
              || mentionsAny(tree.getIndex(), Set.of("sp", "pc"))) {
            error(
                tree,
                "the index "
                    + tree.getIndex()
                    + " must be built on "
                    + roots
                    + " rather than on sp or pc, so that it carries the case's bias; otherwise "
                    + "C2 computes it ahead of the dispatch for every operation");
          }
        }
      }.scan(new TreePath(new TreePath(unit), method.getBody()), null);
    }

    // -------------------------------------------------------------------------------------------
    // Translation

    private String translate() {
      final Map<ReturnTree, Boolean> tails = new HashMap<>();
      markTails(method.getBody(), true, tails);
      final List<Edit> identifiers = new ArrayList<>();
      final TreePath body = new TreePath(new TreePath(unit), method.getBody());
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
          final String replacement = biasedIndex(tree.getName().toString());
          if (replacement != null) {
            final Tree parent = getCurrentPath().getParentPath().getLeaf();
            final boolean whole =
                parent instanceof VariableTree variable && variable.getInitializer() == tree;
            final boolean simple = whole || replacement.equals("true");
            identifiers.add(
                new Edit(start(tree), end(tree), simple ? replacement : "(" + replacement + ")"));
          }
          return null;
        }
      }.scan(body, null);
      // the expression helpers, each copied where its call stands
      final List<Edit> expressions = new ArrayList<>();
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitMethodInvocation(final MethodInvocationTree tree, final Void unused) {
          final InlineHelper helper = helperCalled(tree);
          if (helper != null && helper.isExpression()) {
            final List<String> arguments = new ArrayList<>();
            for (final ExpressionTree argument : tree.getArguments()) {
              arguments.add(render(argument, identifiers));
            }
            expressions.add(new Edit(start(tree), end(tree), helper.copy(arguments)));
            return null;
          }
          return super.visitMethodInvocation(tree, unused);
        }
      }.scan(body, null);
      final List<Edit> inner = new ArrayList<>(expressions);
      inner.addAll(outside(identifiers, expressions));
      // the returns and the helpers that run, rendered with the edits inside them
      final List<Edit> statements = new ArrayList<>();
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitReturn(final ReturnTree tree, final Void unused) {
          final boolean tail = tails.getOrDefault(tree, false);
          final String text;
          if (isDone(tree.getExpression())) {
            final List<? extends ExpressionTree> arguments =
                ((MethodInvocationTree) tree.getExpression()).getArguments();
            text =
                "cost = "
                    + render(arguments.get(0), inner)
                    + ";\nstep = "
                    + render(arguments.get(1), inner)
                    + ";\ndelta = "
                    + render(arguments.get(2), inner)
                    + ";"
                    + (tail ? "" : "\nbreak;");
          } else {
            text = tail ? "" : "break;";
          }
          statements.add(new Edit(start(tree), end(tree), text));
          return null;
        }

        @Override
        public Void visitExpressionStatement(
            final ExpressionStatementTree tree, final Void unused) {
          if (tree.getExpression() instanceof MethodInvocationTree call) {
            final InlineHelper helper = helperCalled(call);
            if (helper != null && !helper.isExpression()) {
              final List<String> arguments = new ArrayList<>();
              for (final ExpressionTree argument : call.getArguments()) {
                arguments.add(render(argument, inner));
              }
              statements.add(new Edit(start(tree), end(tree), helper.copy(arguments)));
              return null;
            }
          }
          return super.visitExpressionStatement(tree, unused);
        }
      }.scan(body, null);
      final List<Edit> edits = new ArrayList<>(statements);
      edits.addAll(outside(inner, statements));
      return apply(start(method.getBody()) + 1, end(method.getBody()) - 1, edits);
    }

    /** The helper a call is to, if it is one. */
    private InlineHelper helperCalled(final MethodInvocationTree call) {
      return call.getMethodSelect() instanceof IdentifierTree select
          ? helpers.get(select.getName().toString())
          : null;
    }

    /** The edits that do not fall inside any of the outer ones. */
    private static List<Edit> outside(final List<Edit> edits, final List<Edit> outer) {
      final List<Edit> kept = new ArrayList<>();
      for (final Edit edit : edits) {
        if (outer.stream().noneMatch(o -> o.start() <= edit.start() && edit.end() <= o.end())) {
          kept.add(edit);
        }
      }
      return kept;
    }

    private String render(final Tree tree, final List<Edit> identifiers) {
      final List<Edit> inside = new ArrayList<>();
      for (final Edit edit : identifiers) {
        if (start(tree) <= edit.start() && edit.end() <= end(tree)) {
          inside.add(edit);
        }
      }
      return apply(start(tree), end(tree), inside);
    }

    private String apply(final long from, final long to, final List<Edit> edits) {
      return EvmV2LoopGenerator.apply(source, from, to, edits);
    }

    private String biasedIndex(final String name) {
      return switch (name) {
        case "top" -> "base + (" + constant + " << 4) - 4";
        case "next" -> "base + (" + constant + " << 4)";
        case "at" -> "pcBase + (" + constant + " << 4)";
        // the loop is the untraced execution; the operation classes pass false
        case "untraced" -> "true";
        default -> null;
      };
    }

    private long start(final Tree tree) {
      return positions.getStartPosition(unit, tree);
    }

    private long end(final Tree tree) {
      return positions.getEndPosition(unit, tree);
    }

    /**
     * Marks the returns after which control reaches the end of the case anyway, which need no
     * break. A return FALLBACK in that position becomes nothing, so the statement before it is in
     * that position too.
     */
    private static void markTails(
        final StatementTree statement, final boolean tail, final Map<ReturnTree, Boolean> tails) {
      if (statement instanceof BlockTree block) {
        final List<? extends StatementTree> statements = block.getStatements();
        int last = statements.size() - 1;
        while (tail
            && last >= 0
            && statements.get(last) instanceof ReturnTree fallback
            && isFallback(fallback.getExpression())) {
          tails.put(fallback, true);
          last--;
        }
        for (int i = 0; i <= last; i++) {
          markTails(statements.get(i), tail && i == last, tails);
        }
      } else if (statement instanceof IfTree ifTree) {
        markTails(ifTree.getThenStatement(), tail, tails);
        if (ifTree.getElseStatement() != null) {
          markTails(ifTree.getElseStatement(), tail, tails);
        }
      } else if (statement instanceof ReturnTree returnTree) {
        tails.put(returnTree, tail);
      }
    }
  }

  /** A replacement of the source text between two positions. */
  private record Edit(long start, long end, String text) {}

  /** The source text between two positions, with the edits that fall inside them applied. */
  private static String apply(
      final String source, final long from, final long to, final List<Edit> edits) {
    final List<Edit> sorted = new ArrayList<>(edits);
    sorted.sort((a, b) -> Long.compare(a.start(), b.start()));
    final StringBuilder out = new StringBuilder();
    long at = from;
    for (final Edit edit : sorted) {
      if (edit.start() < from || edit.end() > to) {
        continue;
      }
      out.append(source, (int) at, (int) edit.start()).append(edit.text());
      at = edit.end();
    }
    out.append(source, (int) at, (int) to);
    return out.toString();
  }

  /**
   * A helper of {@code EvmLoopInlining} marked {@code @InlineInEvmLoop} without opcodes. The
   * operation classes call it like any method; the generator copies its body into every case that
   * calls it, with its parameters and locals renamed after it, so that the loop holds no call.
   */
  private static final class InlineHelper {
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final String source;
    private final MethodTree method;
    private final List<String> errors;
    private final Map<String, String> types = new HashMap<>();
    private final Set<String> locals = new HashSet<>();

    /** The helper's name, which the cases call it by. */
    final String name;

    /** Its parameters, in order. */
    final List<String> parameters = new ArrayList<>();

    /** The parameters it indexes the stack with, which the cases must build on top or next. */
    final Set<String> stackIndexParameters = new HashSet<>();

    /** The constants of EvmLoopInlining it names, which EVM.java then has to import. */
    final Set<String> named = new TreeSet<>();

    /** The expression a helper that computes a value returns, or null for one that runs. */
    private final ExpressionTree returned;

    InlineHelper(
        final CompilationUnitTree unit,
        final SourcePositions positions,
        final String source,
        final ClassTree owner,
        final MethodTree method,
        final List<String> errors) {
      this.unit = unit;
      this.positions = positions;
      this.source = source;
      this.method = method;
      this.errors = errors;
      this.name = method.getName().toString();
      if (!owner.getSimpleName().contentEquals("EvmLoopInlining")) {
        error(
            method,
            "a helper copied into the loop belongs in EvmLoopInlining, which the operations and"
                + " EVM.java import it from");
      }
      final String returnType = method.getReturnType().toString();
      final List<? extends StatementTree> statements = method.getBody().getStatements();
      if (returnType.equals("void")) {
        returned = null;
      } else if (Set.of("boolean", "int", "long").contains(returnType)
          && statements.size() == 1
          && statements.get(0) instanceof ReturnTree returnTree
          && returnTree.getExpression() != null) {
        returned = returnTree.getExpression();
      } else {
        returned = null;
        error(
            method,
            "a helper copied into the loop either returns nothing or is one return of a boolean,"
                + " int or long expression");
      }
      if (!method.getModifiers().getFlags().contains(Modifier.STATIC)) {
        error(method, "a helper copied into the loop is static");
      }
      for (final VariableTree parameter : method.getParameters()) {
        final String parameterName = parameter.getName().toString();
        final String type = parameter.getType().toString();
        if (!parameter.getModifiers().getFlags().contains(Modifier.FINAL)) {
          error(parameter, parameterName + " must be final");
        }
        final boolean passedThrough =
            type.equals("long[]") || type.equals("Code") || PASSED_THROUGH.contains(parameterName);
        if (passedThrough
            ? !PASSED_THROUGH.contains(parameterName) || !CONTEXT.get(parameterName).equals(type)
            : !Set.of("int", "long", "boolean").contains(type)) {
          error(
              parameter,
              "a helper takes the stack as s, the code as codeObject, and ints, longs and booleans");
        }
        parameters.add(parameterName);
        types.put(parameterName, type);
      }
      check();
    }

    private void error(final Tree tree, final String message) {
      final long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree));
      errors.add("EvmLoopInlining." + name + " (line " + line + "): " + message);
    }

    private void check() {
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitVariable(final VariableTree tree, final Void unused) {
          locals.add(tree.getName().toString());
          return super.visitVariable(tree, unused);
        }
      }.scan(method.getBody(), null);
      final Set<String> intParameters = new HashSet<>();
      parameters.stream().filter(p -> types.get(p).equals("int")).forEach(intParameters::add);
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitForLoop(final ForLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitForLoop(tree, unused);
        }

        @Override
        public Void visitEnhancedForLoop(final EnhancedForLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitEnhancedForLoop(tree, unused);
        }

        @Override
        public Void visitWhileLoop(final WhileLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitWhileLoop(tree, unused);
        }

        @Override
        public Void visitDoWhileLoop(final DoWhileLoopTree tree, final Void unused) {
          error(tree, "no loops: every loop head is an OSR entry point; write the limbs out");
          return super.visitDoWhileLoop(tree, unused);
        }

        @Override
        public Void visitNewClass(final NewClassTree tree, final Void unused) {
          error(tree, "no allocation: it makes C2 keep the loop's locals in memory");
          return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitNewArray(final NewArrayTree tree, final Void unused) {
          error(tree, "no allocation: it makes C2 keep the loop's locals in memory");
          return super.visitNewArray(tree, unused);
        }

        @Override
        public Void visitLambdaExpression(final LambdaExpressionTree tree, final Void unused) {
          error(tree, "no lambdas: they allocate and call");
          return null;
        }

        @Override
        public Void visitMemberReference(final MemberReferenceTree tree, final Void unused) {
          error(tree, "no method references: they allocate and call");
          return null;
        }

        @Override
        public Void visitTry(final TryTree tree, final Void unused) {
          error(tree, "no try: halts belong to the operation's implementation");
          return super.visitTry(tree, unused);
        }

        @Override
        public Void visitThrow(final ThrowTree tree, final Void unused) {
          error(tree, "no throw: halts belong to the operation's implementation");
          return super.visitThrow(tree, unused);
        }

        @Override
        public Void visitSynchronized(final SynchronizedTree tree, final Void unused) {
          error(tree, "no synchronization");
          return super.visitSynchronized(tree, unused);
        }

        @Override
        public Void visitSwitch(final SwitchTree tree, final Void unused) {
          error(tree, "no switch: a nested table jump costs bytecode and a second indirect jump");
          return super.visitSwitch(tree, unused);
        }

        @Override
        public Void visitSwitchExpression(final SwitchExpressionTree tree, final Void unused) {
          error(tree, "no switch: a nested table jump costs bytecode and a second indirect jump");
          return super.visitSwitchExpression(tree, unused);
        }

        @Override
        public Void visitReturn(final ReturnTree tree, final Void unused) {
          if (returned == null) {
            error(tree, "a helper copied into the loop runs to its end; it has no return");
          }
          return super.visitReturn(tree, unused);
        }

        @Override
        public Void visitLiteral(final LiteralTree tree, final Void unused) {
          if (tree.getKind() == Tree.Kind.STRING_LITERAL) {
            error(tree, "no strings: they allocate");
          }
          return null;
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree tree, final Void unused) {
          final String called = tree.getMethodSelect().toString();
          if (tree.getMethodSelect() instanceof IdentifierTree) {
            named.add(called);
          } else if (tree.getMethodSelect() instanceof MemberSelectTree select
              && select.getExpression() instanceof IdentifierTree qualifier
              && CONSTANTS.contains(qualifier.getName().toString())) {
            named.add(qualifier.getName().toString());
          }
          if (!CALLS.contains(called) || called.equals("done")) {
            error(
                tree, "the call " + called + " is not one a helper copied into the loop may make");
          }
          for (final ExpressionTree argument : tree.getArguments()) {
            scan(argument, unused);
          }
          return null;
        }

        @Override
        public Void visitMemberSelect(final MemberSelectTree tree, final Void unused) {
          if (!tree.getIdentifier().contentEquals("length") && !FIELDS.contains(tree.toString())) {
            error(tree, "the field " + tree + " is not one a helper may read");
          }
          if (!isClassName(tree.getExpression())) {
            scan(tree.getExpression(), unused);
          }
          return null;
        }

        @Override
        public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
          final String id = tree.getName().toString();
          if (CONSTANTS.contains(id) && !parameters.contains(id) && !locals.contains(id)) {
            named.add(id);
          } else if (!parameters.contains(id) && !locals.contains(id)) {
            error(tree, id + " is neither a parameter, a local nor a constant a helper may read");
          }
          return null;
        }

        @Override
        public Void visitAssignment(final AssignmentTree tree, final Void unused) {
          if (tree.getVariable() instanceof IdentifierTree target
              && parameters.contains(target.getName().toString())) {
            error(target, "a helper does not assign its parameters");
          }
          return super.visitAssignment(tree, unused);
        }

        @Override
        public Void visitArrayAccess(final ArrayAccessTree tree, final Void unused) {
          if (tree.getExpression() instanceof IdentifierTree array
              && array.getName().contentEquals("s")) {
            final Set<String> indices = new HashSet<>();
            new TreeScanner<Void, Void>() {
              @Override
              public Void visitIdentifier(final IdentifierTree identifier, final Void ignored) {
                if (intParameters.contains(identifier.getName().toString())) {
                  indices.add(identifier.getName().toString());
                }
                return null;
              }
            }.scan(tree.getIndex(), null);
            if (indices.isEmpty()) {
              error(tree, "the index " + tree.getIndex() + " must be built on a parameter");
            }
            stackIndexParameters.addAll(indices);
          }
          return super.visitArrayAccess(tree, unused);
        }
      }.scan(method.getBody(), null);
    }

    /**
     * The helper's body for one call, as a block that first binds its parameters to the call's
     * arguments. The stack needs no binding, as a case passes the loop's own.
     */
    String copy(final List<String> arguments) {
      if (returned != null) {
        final List<Edit> edits = new ArrayList<>();
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
            final int index = parameters.indexOf(tree.getName().toString());
            if (index >= 0 && !PASSED_THROUGH.contains(parameters.get(index))) {
              edits.add(new Edit(start(tree), end(tree), "(" + arguments.get(index) + ")"));
            }
            return null;
          }
        }.scan(returned, null);
        return "(" + apply(source, start(returned), end(returned), edits) + ")";
      }
      final StringBuilder out =
          new StringBuilder("{\n// automatically copied from EvmLoopInlining.")
              .append(name)
              .append("; edit it there\n");
      for (int i = 0; i < parameters.size(); i++) {
        final String parameter = parameters.get(i);
        if (!PASSED_THROUGH.contains(parameter)) {
          out.append("final ")
              .append(types.get(parameter))
              .append(' ')
              .append(renamed(parameter))
              .append(" = ")
              .append(arguments.get(i))
              .append(";\n");
        }
      }
      final List<Edit> edits = new ArrayList<>();
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
          final String id = tree.getName().toString();
          if ((parameters.contains(id) && !PASSED_THROUGH.contains(id)) || locals.contains(id)) {
            edits.add(new Edit(start(tree), end(tree), renamed(id)));
          }
          return null;
        }

        @Override
        public Void visitVariable(final VariableTree tree, final Void unused) {
          final String id = tree.getName().toString();
          final long at = source.indexOf(id, (int) end(tree.getType()));
          edits.add(new Edit(at, at + id.length(), renamed(id)));
          return super.visitVariable(tree, unused);
        }
      }.scan(method.getBody(), null);
      return out.append(
              apply(source, start(method.getBody()) + 1, end(method.getBody()) - 1, edits).strip())
          .append("\n}")
          .toString();
    }

    /** Whether the helper computes a value, and so is copied where its call stands in a case. */
    boolean isExpression() {
      return returned != null;
    }

    private String renamed(final String id) {
      return name + Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private long start(final Tree tree) {
      return positions.getStartPosition(unit, tree);
    }

    private long end(final Tree tree) {
      return positions.getEndPosition(unit, tree);
    }
  }

  /** Whether an expression assigns, increments or calls anything but an intrinsic. */
  private static boolean hasSideEffects(final ExpressionTree expression) {
    final boolean[] found = {false};
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitAssignment(final AssignmentTree tree, final Void unused) {
        found[0] = true;
        return null;
      }

      @Override
      public Void visitCompoundAssignment(final CompoundAssignmentTree tree, final Void unused) {
        found[0] = true;
        return null;
      }

      @Override
      public Void visitUnary(final UnaryTree tree, final Void unused) {
        switch (tree.getKind()) {
          case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT ->
              found[0] = true;
          default -> {
            // no assignment
          }
        }
        return super.visitUnary(tree, unused);
      }

      @Override
      public Void visitMethodInvocation(final MethodInvocationTree tree, final Void unused) {
        if (!(tree.getMethodSelect() instanceof MemberSelectTree select
            && isClassName(select.getExpression()))) {
          found[0] = true;
        }
        return super.visitMethodInvocation(tree, unused);
      }
    }.scan(expression, null);
    return found[0];
  }

  /** Whether an expression names one of the classes whose intrinsics an inline method may use. */
  private static boolean isClassName(final ExpressionTree expression) {
    return expression instanceof IdentifierTree identifier
        && (identifier.getName().contentEquals("Long")
            || identifier.getName().contentEquals("Math"));
  }

  private static boolean isDone(final ExpressionTree expression) {
    return expression instanceof MethodInvocationTree call
        && (call.getMethodSelect().toString().equals("done")
            || call.getMethodSelect().toString().equals("jumped"))
        && call.getArguments().size() == 3;
  }

  private static boolean isFallback(final ExpressionTree expression) {
    return expression instanceof IdentifierTree identifier
        && identifier.getName().contentEquals("FALLBACK");
  }

  static String upperSnake(final String camel) {
    return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
  }

  // ---------------------------------------------------------------------------------------------
  // Generation

  static String generate(final String evm, final List<SwitchCase> cases) {
    final StringBuilder table = new StringBuilder();
    table
        .append(TABLE_BEGIN)
        .append(" for the copies of the @InlineInEvmLoop methods of the v2\n")
        .append("// operations below; do not edit, run ./gradlew :evm:generateEvmV2Loop\n");
    for (int i = 0; i < cases.size(); i++) {
      table
          .append("private static final int ")
          .append(cases.get(i).constant())
          .append(" = ")
          .append(i + 1)
          .append(";\n");
    }
    table.append(
        "\n// Per opcode: its case in the low byte, and above it a bias of minus the case times 16,\n"
            + "// which the case's stack and code indices cancel again; see runToHaltV2Untraced.\n"
            + "private static final int[] DISPATCH = new int[256];\n\nstatic {\n");
    for (final SwitchCase switchCase : cases) {
      for (final int[] run : runs(switchCase.opcodes())) {
        table
            .append("dispatch(")
            .append(switchCase.constant())
            .append(String.format(Locale.ROOT, ", 0x%02x, 0x%02x", run[0], run[1]))
            .append(");\n");
      }
    }
    table.append(
        "}\n\nprivate static void dispatch(final int inlineCase, final int first, final int last) {\n"
            + "for (int op = first; op <= last; op++) {\n"
            + "DISPATCH[op] = (-(inlineCase << 4) << 8) | inlineCase;\n}\n}\n");
    table.append(TABLE_END);

    final StringBuilder switchCases = new StringBuilder();
    switchCases
        .append(CASES_BEGIN)
        .append(": copies of the @InlineInEvmLoop methods of the v2 operations;\n")
        .append("// edit those, then run ./gradlew :evm:generateEvmV2Loop\n");
    for (final SwitchCase switchCase : cases) {
      final String source = switchCase.owner() + "." + switchCase.method();
      switchCases
          .append("// BEGIN automatically copied from ")
          .append(source)
          .append("; edit it there\n")
          .append("case ")
          .append(switchCase.constant())
          .append(" -> {\n")
          .append(switchCase.body().strip())
          .append("\n}\n")
          .append("// END automatically copied from ")
          .append(source)
          .append('\n');
    }
    switchCases.append(CASES_END);

    return replaceRegion(
        replaceRegion(evm, TABLE_BEGIN, TABLE_END, table.toString(), 2),
        CASES_BEGIN,
        CASES_END,
        switchCases.toString(),
        8);
  }

  private static List<int[]> runs(final Set<Integer> opcodes) {
    final List<int[]> runs = new ArrayList<>();
    for (final int op : opcodes) {
      if (!runs.isEmpty() && runs.get(runs.size() - 1)[1] == op - 1) {
        runs.get(runs.size() - 1)[1] = op;
      } else {
        runs.add(new int[] {op, op});
      }
    }
    return runs;
  }

  private static String replaceRegion(
      final String text,
      final String begin,
      final String end,
      final String replacement,
      final int indent) {
    final int from = text.indexOf(begin);
    final int to = text.indexOf(end);
    if (from < 0 || to < from) {
      throw new IllegalStateException("EVM.java lacks the markers " + begin + " ... " + end);
    }
    final int lineStart = text.lastIndexOf('\n', from) + 1;
    final String pad = " ".repeat(indent);
    final StringBuilder indented = new StringBuilder();
    for (final String line : replacement.split("\n", -1)) {
      indented.append(line.isBlank() ? "" : pad + line.strip()).append('\n');
    }
    final int lineEnd = text.indexOf('\n', to);
    return text.substring(0, lineStart) + indented + text.substring(lineEnd + 1);
  }
}

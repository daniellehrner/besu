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
 * Generates the inline arms of the untraced EVM v2 loop in EVM.java from the methods of the
 * operation classes marked {@code @InlineInEvmLoop}, and checks those methods against the rules
 * that keep the loop fast. The rules and why each exists are documented on {@code InlineInEvmLoop}.
 *
 * <p>Usage: {@code EvmV2LoopGenerator write|check <operations dir> <EVM.java> [<marker file>]}.
 * {@code write} rewrites the generated regions of EVM.java; {@code check} fails if any rule is
 * broken or EVM.java does not hold what the arms generate, and on success touches the marker file.
 */
public final class EvmV2LoopGenerator {

  static final String TABLE_BEGIN = "// BEGIN GENERATED arm table";
  static final String TABLE_END = "// END GENERATED arm table";
  static final String ARMS_BEGIN = "// BEGIN GENERATED arms";
  static final String ARMS_END = "// END GENERATED arms";

  /** Where the constants and helpers an arm may name come from, for the arms and EVM.java alike. */
  static final String ARMS_CLASS = "org.hyperledger.besu.evm.v2.operation.EvmLoopInlining";

  /** The loop state an arm may take as a parameter, by name, with its type. */
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
    CONTEXT.put("frame", "MessageFrame");
    CONTEXT.put("constantinople", "boolean");
    CONTEXT.put("shanghai", "boolean");
  }

  /** The locals of the loop that an arm's own locals must not shadow. */
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
          "constantinople",
          "shanghai",
          "result");

  /** Constants an arm may read. Everything else it reads must be a parameter or its own local. */
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
   * The only calls an arm may make: intrinsics, the VarHandle for words in byte arrays, and small
   * accessors C2 inlines at any call site. EvmLoopMethodSizeTest checks the accessors stay small.
   */
  private static final Set<String> CALLS =
      Set.of(
          "done",
          "isJumpDestinationV2",
          "Long.bitCount",
          "Long.compareUnsigned",
          "Long.divideUnsigned",
          "Long.remainderUnsigned",
          "Long.numberOfLeadingZeros",
          "Long.numberOfTrailingZeros",
          "Math.unsignedMultiplyHigh",
          "Math.multiplyHigh",
          "LONG_BE.get",
          "LONG_BE.set",
          "frame.memoryArrayV2",
          "frame.memoryByteSize",
          "frame.inputDataArrayIfPresent",
          "codeObject.pushValues",
          "codeObject.pushBase",
          "codeObject.pushBits",
          "codeObject.pushWide",
          "codeObject.getJumpDestBitMask");

  /** Fields an arm may read through a qualifier, besides the length of an array. */
  private static final Set<String> FIELDS = Set.of("Long.MIN_VALUE", "Long.MAX_VALUE");

  private EvmV2LoopGenerator() {}

  /** One arm: the method it comes from, its place in the loop and the opcodes it runs. */
  private record SwitchArm(
      String owner, String method, String constant, Set<Integer> opcodes, String body) {

    /** The arm's lowest opcode, which decides where it comes in the loop. */
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
    final List<SwitchArm> arms = parse(operations, named, errors);
    checkNamesResolveToArms(evmFile, named, errors);
    if (!errors.isEmpty()) {
      System.err.println(
          "The EVM v2 loop arms in "
              + operations
              + " break the rules documented on @InlineInEvmLoop:");
      errors.forEach(e -> System.err.println("  " + e));
      System.exit(1);
    }
    final String current = Files.readString(evmFile, UTF_8);
    final String generated = generate(current, arms);
    if (args[0].equals("write")) {
      Files.writeString(evmFile, generated, UTF_8);
      System.out.println("Wrote " + arms.size() + " arms into " + evmFile);
    } else {
      if (!withoutWhitespace(generated).equals(withoutWhitespace(current))) {
        System.err.println(
            evmFile
                + " does not hold the arms of "
                + operations
                + ". Edit an arm in its operation's class, then run ./gradlew"
                + " :evm:generateEvmV2Loop.");
        System.exit(1);
      }
      if (args.length > 3) {
        final Path marker = Path.of(args[3]);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "checked " + arms.size() + " arms\n", UTF_8);
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

  private static List<SwitchArm> parse(
      final Path operations, final Set<String> named, final List<String> errors)
      throws IOException {
    final List<Path> sources;
    try (Stream<Path> files = Files.list(operations)) {
      sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    }
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    final List<SwitchArm> arms = new ArrayList<>();
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
      for (final CompilationUnitTree unit : task.parse()) {
        final String source = unit.getSourceFile().getCharContent(true).toString();
        for (final Tree type : unit.getTypeDecls()) {
          if (!(type instanceof ClassTree classTree)) {
            continue;
          }
          for (final Tree member : classTree.getMembers()) {
            if (member instanceof MethodTree method && armAnnotation(method) != null) {
              final Kernel kernel = new Kernel(unit, positions, source, classTree, method, errors);
              arms.add(kernel.toSwitchArm());
              named.addAll(kernel.named);
              checkNamesResolveToArms(
                  unit, classTree, kernel.named, classTree.getSimpleName().toString(), errors);
            }
          }
        }
      }
    }
    if (arms.isEmpty()) {
      errors.add("no @InlineInEvmLoop methods found in " + operations);
    }
    arms.sort(Comparator.comparingInt(SwitchArm::first));
    final Map<Integer, String> owners = new HashMap<>();
    final Map<String, String> constants = new HashMap<>();
    for (final SwitchArm arm : arms) {
      final String where = arm.owner() + "." + arm.method();
      final String other = constants.put(arm.constant(), where);
      if (other != null) {
        errors.add(where + " and " + other + " need names of their own, as the loop names arms");
      }
      for (final int opcode : arm.opcodes()) {
        final String owner = owners.put(opcode, where);
        if (owner != null) {
          errors.add(
              String.format(
                  Locale.ROOT, "opcode 0x%02x belongs to both %s and %s", opcode, owner, where));
        }
      }
    }
    return arms;
  }

  /**
   * An arm names constants and helpers without a qualifier, in its operation's class and, once
   * generated, inside EVM. Java resolves such a name by where it stands, and a member of the
   * enclosing class hides a static import of the same name, overloads included. So each name has to
   * come from EvmLoopInlining in both places, and neither class may declare a member of that name.
   */
  private static void checkNamesResolveToArms(
      final Path evmFile, final Set<String> named, final List<String> errors) throws IOException {
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
          used.removeAll(Set.of("done", "FALLBACK"));
          checkNamesResolveToArms(unit, classTree, used, evmFile.toString(), errors);
        }
      }
    }
  }

  private static void checkNamesResolveToArms(
      final CompilationUnitTree unit,
      final ClassTree classTree,
      final Set<String> names,
      final String where,
      final List<String> errors) {
    final Set<String> imported = new HashSet<>();
    for (final ImportTree anImport : unit.getImports()) {
      final String name = anImport.getQualifiedIdentifier().toString();
      if (anImport.isStatic() && name.startsWith(ARMS_CLASS + ".")) {
        imported.add(name.substring(ARMS_CLASS.length() + 1));
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
        errors.add(where + " has to import " + name + " statically from " + ARMS_CLASS);
      }
      if (declared.contains(name)) {
        errors.add(
            where
                + " declares "
                + name
                + ", which hides the one the arms mean; give it a name of its own");
      }
    }
  }

  private static AnnotationTree armAnnotation(final MethodTree method) {
    for (final AnnotationTree annotation : method.getModifiers().getAnnotations()) {
      if (annotation.getAnnotationType().toString().equals("InlineInEvmLoop")) {
        return annotation;
      }
    }
    return null;
  }

  /** One arm method: its checks and its translation into loop code. */
  private static final class Kernel {
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final String source;
    private final ClassTree owner;
    private final MethodTree method;
    private final List<String> errors;
    private final String constant;

    /** The constants and helpers of EvmLoopInlining the arm names without a qualifier. */
    final Set<String> named = new TreeSet<>();

    private final Set<String> parameters = new HashSet<>();
    private final Set<String> locals = new HashSet<>();
    private final Map<String, List<ExpressionTree>> assignments = new HashMap<>();

    Kernel(
        final CompilationUnitTree unit,
        final SourcePositions positions,
        final String source,
        final ClassTree owner,
        final MethodTree method,
        final List<String> errors) {
      this.unit = unit;
      this.positions = positions;
      this.source = source;
      this.owner = owner;
      this.method = method;
      this.errors = errors;
      this.constant = "INLINE_" + upperSnake(method.getName().toString());
    }

    SwitchArm toSwitchArm() {
      final Set<Integer> opcodes = opcodes();
      checkSignature();
      collectLocals();
      checkBody();
      return new SwitchArm(
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
      for (final ExpressionTree argument : armAnnotation(method).getArguments()) {
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
        error(method, "an arm is a static method returning long");
      }
      for (final VariableTree parameter : method.getParameters()) {
        final String name = parameter.getName().toString();
        final String expected = CONTEXT.get(name);
        if (expected == null) {
          error(parameter, name + " is not loop state an arm can take; see @InlineInEvmLoop");
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
          if (!CALLS.contains(name)) {
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
            error(tree, "the field " + name + " is not one an arm may read");
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
                    + " is neither a parameter, a local nor a constant an arm may read; a field "
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
            error(target, "an arm changes the loop's state only through done()");
          }
        }

        @Override
        public Void visitReturn(final ReturnTree tree, final Void unused) {
          if (!isDone(tree.getExpression()) && !isFallback(tree.getExpression())) {
            error(tree, "an arm returns done(cost, step, delta) or FALLBACK, nothing else");
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
                    + " rather than on sp or pc, so that it carries the arm's bias; otherwise "
                    + "C2 computes it ahead of the dispatch for every operation");
          }
        }
      }.scan(new TreePath(new TreePath(unit), method.getBody()), null);
    }

    // -------------------------------------------------------------------------------------------
    // Translation

    private record Edit(long start, long end, String text) {}

    private String translate() {
      final Map<ReturnTree, Boolean> tails = new HashMap<>();
      markTails(method.getBody(), true, tails);
      final List<Edit> identifiers = new ArrayList<>();
      final List<Edit> returns = new ArrayList<>();
      final TreePath body = new TreePath(new TreePath(unit), method.getBody());
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
          final String replacement = biasedIndex(tree.getName().toString());
          if (replacement != null) {
            final Tree parent = getCurrentPath().getParentPath().getLeaf();
            final boolean whole =
                parent instanceof VariableTree variable && variable.getInitializer() == tree;
            identifiers.add(
                new Edit(start(tree), end(tree), whole ? replacement : "(" + replacement + ")"));
          }
          return null;
        }
      }.scan(body, null);
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
                    + render(arguments.get(0), identifiers)
                    + ";\nstep = "
                    + render(arguments.get(1), identifiers)
                    + ";\ndelta = "
                    + render(arguments.get(2), identifiers)
                    + ";"
                    + (tail ? "" : "\nbreak;");
          } else {
            text = tail ? "" : "break;";
          }
          returns.add(new Edit(start(tree), end(tree), text));
          return null;
        }
      }.scan(body, null);
      final List<Edit> edits = new ArrayList<>(returns);
      for (final Edit identifier : identifiers) {
        if (returns.stream()
            .noneMatch(r -> r.start() <= identifier.start() && identifier.end() <= r.end())) {
          edits.add(identifier);
        }
      }
      return apply(start(method.getBody()) + 1, end(method.getBody()) - 1, edits);
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

    private String biasedIndex(final String name) {
      return switch (name) {
        case "top" -> "base + (" + constant + " << 4) - 4";
        case "next" -> "base + (" + constant + " << 4)";
        case "at" -> "pcBase + (" + constant + " << 4)";
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
     * Marks the returns after which control reaches the end of the arm anyway, which need no break.
     * A return FALLBACK in that position becomes nothing, so the statement before it is in that
     * position too.
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

  /** Whether an expression names one of the classes whose intrinsics an arm may use. */
  private static boolean isClassName(final ExpressionTree expression) {
    return expression instanceof IdentifierTree identifier
        && (identifier.getName().contentEquals("Long")
            || identifier.getName().contentEquals("Math"));
  }

  private static boolean isDone(final ExpressionTree expression) {
    return expression instanceof MethodInvocationTree call
        && call.getMethodSelect().toString().equals("done")
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

  static String generate(final String evm, final List<SwitchArm> arms) {
    final StringBuilder table = new StringBuilder();
    table
        .append(TABLE_BEGIN)
        .append(" for the copies of the @InlineInEvmLoop methods of the v2\n")
        .append("// operations below; do not edit, run ./gradlew :evm:generateEvmV2Loop\n");
    for (int i = 0; i < arms.size(); i++) {
      table
          .append("private static final int ")
          .append(arms.get(i).constant())
          .append(" = ")
          .append(i + 1)
          .append(";\n");
    }
    table.append(
        "\n// Per opcode: the arm in the low byte, and above it a bias of minus the arm times 16,\n"
            + "// which the arm's stack and code indices cancel again; see runToHaltV2Untraced.\n"
            + "private static final int[] DISPATCH = new int[256];\n\nstatic {\n");
    for (final SwitchArm arm : arms) {
      for (final int[] run : runs(arm.opcodes())) {
        table
            .append("dispatch(")
            .append(arm.constant())
            .append(String.format(Locale.ROOT, ", 0x%02x, 0x%02x", run[0], run[1]))
            .append(");\n");
      }
    }
    table.append(
        "}\n\nprivate static void dispatch(final int arm, final int first, final int last) {\n"
            + "for (int op = first; op <= last; op++) {\n"
            + "DISPATCH[op] = (-(arm << 4) << 8) | arm;\n}\n}\n");
    table.append(TABLE_END);

    final StringBuilder switchArms = new StringBuilder();
    switchArms
        .append(ARMS_BEGIN)
        .append(": copies of the @InlineInEvmLoop methods of the v2 operations;\n")
        .append("// edit those, then run ./gradlew :evm:generateEvmV2Loop\n");
    for (final SwitchArm arm : arms) {
      final String source = arm.owner() + "." + arm.method();
      switchArms
          .append("// BEGIN automatically copied from ")
          .append(source)
          .append("; edit it there\n")
          .append("case ")
          .append(arm.constant())
          .append(" -> {\n")
          .append(arm.body().strip())
          .append("\n}\n")
          .append("// END automatically copied from ")
          .append(source)
          .append('\n');
    }
    switchArms.append(ARMS_END);

    return replaceRegion(
        replaceRegion(evm, TABLE_BEGIN, TABLE_END, table.toString(), 2),
        ARMS_BEGIN,
        ARMS_END,
        switchArms.toString(),
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

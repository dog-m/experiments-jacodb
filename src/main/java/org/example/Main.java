package org.example;

import org.jacodb.api.JcAnnotation;
import org.jacodb.api.JcClassOrInterface;
import org.jacodb.api.JcMethod;
import org.jacodb.api.JcSymbol;
import org.jacodb.api.cfg.JcRawAssignInst;
import org.jacodb.api.cfg.JcRawCallExpr;
import org.jacodb.api.cfg.JcRawCallInst;
import org.jacodb.api.cfg.JcRawFieldRef;
import org.jacodb.approximation.Approximations;
import org.jacodb.impl.JacoDB;
import org.jacodb.impl.JcSettings;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public final class Main {
    private static boolean has(int data, int mask) {
        return (data & mask) != 0;
    }

    private static String getModifiers(final int mods) {
        final var modifiers = new StringJoiner(" ");

        final BiConsumer<Integer, String> m = (mask, name) -> {
            if (has(mods, mask)) modifiers.add(name);
        };

        m.accept(Opcodes.ACC_PUBLIC, "public");
        m.accept(Opcodes.ACC_PRIVATE, "private");
        m.accept(Opcodes.ACC_PROTECTED, "protected");

        m.accept(Opcodes.ACC_STATIC, "static");
        m.accept(Opcodes.ACC_FINAL, "final");
        m.accept(Opcodes.ACC_SYNCHRONIZED, "synchronized");
        m.accept(Opcodes.ACC_NATIVE, "native");

        return modifiers.toString();
    }

    private String formatMethodSignature(final JcMethod m) {
        final var parameters = m.getParameters().stream()
                .map(p -> p.getType().getTypeName())
                .collect(Collectors.joining(", "));

        final var modifiers = getModifiers(m.getAccess());

        return modifiers +
                " " +
                m.getEnclosingClass().getName() +
                "#" +
                m.getName() +
                "(" +
                parameters +
                ") -> " +
                m.getReturnType().getTypeName();
    }

    private String formatCallExpr(final JcRawCallExpr expr) {
        return String.format(
                "%s.%s()",
                expr.getDeclaringClass().getTypeName(),
                expr.getMethodName()
        );
    }

    private String formatField(final JcRawFieldRef ref) {
        return String.format(
                "%s.%s",
                ref.getDeclaringClass().getTypeName(),
                ref.getFieldName()
        );
    }

    private static String formatOwner(final String owner) {
        return owner.replace('/', '.');
    }

    private void inspectASM(final MethodNode method) {
        System.out.println("ASM:");
        method.accept(new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                System.out.printf(
                        "[c] %s.%s()%n",
                        formatOwner(owner),
                        name
                );
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                System.out.printf(
                        "[%s] %s.%s%n",
                        opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC
                                ? "r" : "w",
                        formatOwner(owner),
                        name
                );
            }
        });
    }

    private void inspectJacodbInstr(final JcMethod method) {
        System.out.println("jacodb-raw:");

        for (var i : method.getRawInstList()) {
            if (i instanceof JcRawCallInst) {
                final var expr = ((JcRawCallInst) i).getCallExpr();
                System.out.println("[c] " + formatCallExpr(expr));
            }
            if (i instanceof JcRawAssignInst) {
                final var lhv = ((JcRawAssignInst) i).getLhv();
                final var rhv = ((JcRawAssignInst) i).getRhv();

                if (rhv instanceof JcRawCallExpr) {
                    final var expr = (JcRawCallExpr) rhv;
                    System.out.println("[c] " + formatCallExpr(expr));
                }
                if (rhv instanceof JcRawFieldRef) {
                    final var field = (JcRawFieldRef) rhv;
                    System.out.println("[r] " + formatField(field));
                }

                if (lhv instanceof JcRawFieldRef) {
                    final var field = (JcRawFieldRef) lhv;
                    System.out.println("[w] " + formatField(field));
                }
            }
        }
    }

    private static void inspectAnnotations(final List<JcAnnotation> annotations) {
        System.out.println("annotations: " + annotations.size());
        annotations.stream()
                .sorted(Comparator.comparing(JcSymbol::getName))
                .map(JcSymbol::getName)
                .map(n -> "- " + n)
                .forEach(System.out::println);
    }

    private void inspectMethod(final JcMethod method) {
        final var signature = formatMethodSignature(method);
        System.out.println(signature);

        inspectAnnotations(method.getAnnotations());

        /*
        System.out.println("<raw>:");
        for (var i : method.getRawInstList())
            System.out.println(i);
            */

        inspectJacodbInstr(method);
        inspectASM(method.asmNode());

        System.out.println();
    }

    private void inspectMethods(final List<JcMethod> methods) {
        System.out.println("methods: " + methods.size());

        System.out.println();
        methods.stream()
                .sorted(Comparator.comparing(JcSymbol::getName))
                .forEach(this::inspectMethod);
    }

    private void inspectInterfaces(final List<JcClassOrInterface> interfaces) {
        System.out.println("interfaces: " + interfaces.size());
        interfaces.stream()
                .sorted(Comparator.comparing(JcSymbol::getName))
                .map(JcSymbol::getName)
                .map(n -> "- " + n)
                .forEach(System.out::println);
    }

    private void inspectClass(final JcClassOrInterface clazz) {
        System.out.println("Target: " + clazz.getName());

        inspectInterfaces(clazz.getInterfaces());
        inspectMethods(clazz.getDeclaredMethods());
    }

    private void run(final String className) throws Exception {
        final var stdlib = new File("std-library.jar");
        if (!stdlib.exists())
            throw new RuntimeException();

        System.out.println("Instantiating JacoDB...");
        final var database = JacoDB.async(
                new JcSettings()
                        .persistent("X:\\jacodb-index.db")
                        .useProcessJavaRuntime()
                        .installFeatures(Approximations.INSTANCE)
                        .loadByteCode(List.of(stdlib))
        ).get();

        System.out.println("Waiting for background jobs to finish...");
        database.asyncAwaitBackgroundJobs().get();

        final var clazz = database
                .asyncClasspath(
                        List.of(stdlib),
                        List.of(Approximations.INSTANCE))
                .get()
                .findClassOrNull(className);
        assert clazz != null;

        inspectClass(clazz);
    }

    public static void main(String[] args) throws Exception {
        new Main().run(args[0]);
    }
}
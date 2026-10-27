package com.bori.test.t9;

import java.util.List;

/** Java 17 language features: records, sealed types, switch expressions, text blocks, var, pattern instanceof. */
public final class Shapes {
    private Shapes() {}

    public record Point(int x, int y) {
        public Point {
            if (x < 0 || y < 0) throw new IllegalArgumentException("negative");
        }

        public int sum() { return x + y; }
    }

    public sealed interface Shape permits Circle, Square {}

    public record Circle(double radius) implements Shape {}

    public record Square(double side) implements Shape {}

    public static double area(Shape shape) {
        if (shape instanceof Circle c) return Math.PI * c.radius() * c.radius();
        if (shape instanceof Square s) return s.side() * s.side();
        throw new IllegalStateException();
    }

    public static String name(int n) {
        return switch (n) {
            case 1 -> "one";
            case 2 -> "two";
            default -> {
                var text = "many";
                yield text;
            }
        };
    }

    public static String textBlock() {
        var block = """
            Hello
              Java 17
            """;
        return block.trim();
    }

    public static boolean records() {
        var a = new Point(1, 2);
        var b = new Point(1, 2);
        var list = List.of(a, new Point(3, 4));
        return a.equals(b) && a.hashCode() == b.hashCode()
            // R8 renames the record and its fields in release builds, so only the values are fixed.
            && a.toString().matches(".*\\[\\w+=1, \\w+=2]")
            && list.get(1).sum() == 7;
    }

    public static boolean sealed() {
        Class<?>[] permitted = Shape.class.isInterface() ? new Class<?>[] { Circle.class, Square.class } : null;
        return permitted != null && area(new Square(3)) == 9.0 && area(new Circle(1)) > 3.14;
    }
}

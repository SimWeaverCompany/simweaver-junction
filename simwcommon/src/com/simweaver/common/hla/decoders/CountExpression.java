package com.simweaver.common.hla.decoders;

import java.util.Map;

/**
 * Tiny integer expression for length-less array counts, as in RPR notes such as
 * {@code RPRlengthlessArrayLength=Order*Order+2*Order+1}. Grammar:
 * <pre>
 * expr   := term (('+' | '-') term)*
 * term   := factor (('*' | '/') factor)*
 * factor := integer | identifier | '(' expr ')' | '-' factor
 * </pre>
 * Identifiers are record field names; values are looked up when evaluated. Immutable.
 */
final class CountExpression {

    private interface Node {
        long eval(Map<String, Long> vars);
    }

    private final String text;
    private final Node root;

    private CountExpression(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    /**
     * @param text expression
     * @return the parsed expression
     * @throws IllegalArgumentException with the position of the problem
     */
    static CountExpression parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("count expression is null");
        }
        Parser p = new Parser(text);
        Node n = p.expr();
        p.skipSpace();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("unexpected '" + text.charAt(p.pos) + "' at " + p.pos + " in '" + text + "'");
        }
        return new CountExpression(text, n);
    }

    /**
     * @param vars values of earlier record fields
     * @return the value
     * @throws IllegalStateException when a field is missing or a division by zero occurs
     */
    long evaluate(Map<String, Long> vars) {
        return root.eval(vars);
    }

    @Override
    public String toString() {
        return text;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean eat(char c) {
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        Node expr() {
            Node left = term();
            while (true) {
                if (eat('+')) {
                    final Node l = left;
                    final Node r = term();
                    left = new Node() {
                        @Override
                        public long eval(Map<String, Long> v) {
                            return Math.addExact(l.eval(v), r.eval(v));
                        }
                    };
                } else if (eat('-')) {
                    final Node l = left;
                    final Node r = term();
                    left = new Node() {
                        @Override
                        public long eval(Map<String, Long> v) {
                            return Math.subtractExact(l.eval(v), r.eval(v));
                        }
                    };
                } else {
                    return left;
                }
            }
        }

        Node term() {
            Node left = factor();
            while (true) {
                if (eat('*')) {
                    final Node l = left;
                    final Node r = factor();
                    left = new Node() {
                        @Override
                        public long eval(Map<String, Long> v) {
                            return Math.multiplyExact(l.eval(v), r.eval(v));
                        }
                    };
                } else if (eat('/')) {
                    final Node l = left;
                    final Node r = factor();
                    left = new Node() {
                        @Override
                        public long eval(Map<String, Long> v) {
                            long d = r.eval(v);
                            if (d == 0) {
                                throw new IllegalStateException("division by zero");
                            }
                            return l.eval(v) / d;
                        }
                    };
                } else {
                    return left;
                }
            }
        }

        Node factor() {
            skipSpace();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end of '" + s + "'");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Node n = expr();
                if (!eat(')')) {
                    throw new IllegalArgumentException("missing ')' at " + pos + " in '" + s + "'");
                }
                return n;
            }
            if (c == '-') {
                pos++;
                final Node n = factor();
                return new Node() {
                    @Override
                    public long eval(Map<String, Long> v) {
                        return Math.negateExact(n.eval(v));
                    }
                };
            }
            if (Character.isDigit(c)) {
                int start = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
                final long value = Long.parseLong(s.substring(start, pos));
                return new Node() {
                    @Override
                    public long eval(Map<String, Long> v) {
                        return value;
                    }
                };
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                    pos++;
                }
                final String name = s.substring(start, pos);
                return new Node() {
                    @Override
                    public long eval(Map<String, Long> v) {
                        Long x = v.get(name);
                        if (x == null) {
                            throw new IllegalStateException("field '" + name + "' not available (not decoded yet or not an integer)");
                        }
                        return x;
                    }
                };
            }
            throw new IllegalArgumentException("unexpected '" + c + "' at " + pos + " in '" + s + "'");
        }
    }
}

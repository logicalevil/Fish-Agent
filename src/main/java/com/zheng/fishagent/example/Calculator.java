package com.zheng.fishagent.example;

import com.zheng.fishagent.core.FishAgentException;

/**
 * 极简四则运算求值器（递归下降），用于示例工具。
 * 支持 {@code + - * / % ^}、括号与一元正负号。
 */
public final class Calculator {

    private Calculator() {
    }

    public static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new FishAgentException("表达式不能为空");
        }
        Parser parser = new Parser(expression.replace("×", "*").replace("÷", "/").replace("－", "-"));
        double value = parser.parseExpression();
        parser.skipSpaces();
        if (!parser.reachedEnd()) {
            throw new FishAgentException("表达式存在无法解析的内容，位置 " + parser.position());
        }
        return value;
    }

    /**
     * 结果格式化：整数不显示小数位。
     */
    public static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static final class Parser {

        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private int position() {
            return pos;
        }

        private boolean reachedEnd() {
            return pos >= text.length();
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            skipSpaces();
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                char c = peek();
                if (c == '+') {
                    pos++;
                    value += parseTerm();
                } else if (c == '-') {
                    pos++;
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseUnary();
            while (true) {
                char c = peek();
                if (c == '*') {
                    pos++;
                    value *= parseUnary();
                } else if (c == '/') {
                    pos++;
                    double divisor = parseUnary();
                    if (divisor == 0) {
                        throw new FishAgentException("除数不能为 0");
                    }
                    value /= divisor;
                } else if (c == '%') {
                    pos++;
                    value %= parseUnary();
                } else {
                    return value;
                }
            }
        }

        private double parseUnary() {
            char c = peek();
            if (c == '-') {
                pos++;
                return -parseUnary();
            }
            if (c == '+') {
                pos++;
                return parseUnary();
            }
            return parsePower();
        }

        private double parsePower() {
            double base = parsePrimary();
            if (peek() == '^') {
                pos++;
                return Math.pow(base, parseUnary());
            }
            return base;
        }

        private double parsePrimary() {
            char c = peek();
            if (c == '(') {
                pos++;
                double value = parseExpression();
                if (peek() != ')') {
                    throw new FishAgentException("括号不匹配");
                }
                pos++;
                return value;
            }
            int start = pos;
            while (pos < text.length()) {
                char current = text.charAt(pos);
                if (Character.isDigit(current) || current == '.' || current == 'e' || current == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new FishAgentException("无法解析的数字，位置 " + pos);
            }
            try {
                return Double.parseDouble(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new FishAgentException("非法数字：" + text.substring(start, pos));
            }
        }
    }
}

package com.zheng.fishagent.example;

import com.zheng.fishagent.core.FishAgentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculatorTest {

    @Test
    void 应支持四则运算与括号() {
        assertEquals(9.0, Calculator.evaluate("(1+2)*3"));
        assertEquals(7.0, Calculator.evaluate("1 + 2 * 3"));
        assertEquals(0.5, Calculator.evaluate("1/2"));
        assertEquals(1.5, Calculator.evaluate("5 % 2 + 0.5"));
    }

    @Test
    void 应支持幂运算与一元负号() {
        assertEquals(1024.0, Calculator.evaluate("2^10"));
        assertEquals(-6.0, Calculator.evaluate("-2*3"));
        assertEquals(9.0, Calculator.evaluate("(-3)^2"));
    }

    @Test
    void 格式化时整数不带小数位() {
        assertEquals("9", Calculator.format(Calculator.evaluate("(1+2)*3")));
        assertEquals("2.5", Calculator.format(Calculator.evaluate("5/2")));
    }

    @Test
    void 非法表达式应抛出异常() {
        assertThrows(FishAgentException.class, () -> Calculator.evaluate("1+"));
        assertThrows(FishAgentException.class, () -> Calculator.evaluate("(1+2"));
        assertThrows(FishAgentException.class, () -> Calculator.evaluate("1/0"));
    }
}

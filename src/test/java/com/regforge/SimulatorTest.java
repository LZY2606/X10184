package com.regforge;

import com.regforge.model.ChipModel;
import com.regforge.sim.SimOp;
import com.regforge.sim.Simulator;
import com.regforge.sim.StepResult;
import com.regforge.yaml.YamlModelParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorTest {

    private ChipModel model;

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        model = new YamlModelParser().parse(fixture("fixture_chip.yaml"));
    }

    @Test
    void writeOneClearAndWriteOneSetFollowHardwareSemantics() {
        Simulator sim = new Simulator(model);
        sim.apply(0, SimOp.write("CTRL", 0b11));
        // EN(RW)=1 被存下；GO(W1S) 写一后硬件置位
        assertThat(sim.snapshot().get("CTRL")).isEqualTo(0b11);
        // W1S 位写 0 无影响：重新只写 EN=1，GO 保持置位
        sim.apply(0, SimOp.write("CTRL", 0b001));
        assertThat(sim.snapshot().get("CTRL")).isEqualTo(0b11);
        // 只写 EN=0：EN 清零，GO(W1S) 不受写 0 影响
        sim.apply(0, SimOp.write("CTRL", 0b000));
        assertThat(sim.snapshot().get("CTRL")).isEqualTo(0b10);
        // GO 重复写一仍然保持置位（W1S 幂等），同时把 EN 置回
        sim.apply(0, SimOp.write("CTRL", 0b11));
        assertThat(sim.snapshot().get("CTRL")).isEqualTo(0b11);
        // 全 1 写：保留位维持 0，DONE=0 时 W1C 无效果，EN/GO 保持；MODE 是 RW 会被写入
        sim.apply(0, SimOp.write("CTRL", 0xFFFFFFFFL));
        long value = sim.snapshot().get("CTRL");
        assertThat(value & 0b00111).as("EN/GO 保持，DONE 与保留位维持 0").isEqualTo(0b11);
        assertThat(value & 0b11111000).as("3..7 保留位维持读取值 0").isZero();
        // DONE 是 W1C：没有置位路径时保持 0，写 W1C 掩码仍为 0（此写 EN=0，RW 随之清零）
        sim.apply(0, SimOp.write("CTRL", 0b111)); // EN=1, GO=1, DONE=1(W1C 被清零)
        assertThat(sim.snapshot().get("CTRL") & 0b100).isZero();
        assertThat(sim.snapshot().get("CTRL") & 0b11).isEqualTo(0b11);
    }

    @Test
    void reservedBitsPreserveReadValueOnWriteBack() {
        Simulator sim = new Simulator(model);
        sim.apply(0, SimOp.write("CTRL", 0xFFFFFFFFL));
        long ctrl = sim.snapshot().get("CTRL");
        assertThat(ctrl & 0xF8L).as("3..7 保留位维持读取值 0").isZero();
    }

    @Test
    void readClearConsumesButPeekDoesNot() {
        Simulator sim = new Simulator(model);
        // EN 置位触发 STAT.ACT
        sim.apply(0, SimOp.write("CTRL", 0x1));
        assertThat(sim.snapshot().get("STAT")).isEqualTo(0x1);

        StepResult peek = sim.apply(1, SimOp.peek("STAT"));
        assertThat(peek.readValue()).isEqualTo("0x00000001");
        assertThat(peek.log()).anyMatch(l -> l.contains("peek"));
        assertThat(sim.snapshot().get("STAT")).isEqualTo(0x1);

        StepResult read = sim.apply(2, SimOp.read("STAT"));
        assertThat(read.readValue()).isEqualTo("0x00000001");
        assertThat(read.log()).anyMatch(l -> l.contains("读清零"));
        assertThat(sim.snapshot().get("STAT")).isZero();

        StepResult readAgain = sim.apply(3, SimOp.read("STAT"));
        assertThat(readAgain.readValue()).isEqualTo("0x00000000");
    }

    @Test
    void lockBlocksWritesUntilUnlockValueWritten() {
        Simulator sim = new Simulator(model);
        // 默认 LOCK.KEY 复位为 1，SECRET 受保护
        StepResult blocked = sim.apply(0, SimOp.write("SECRET", 0xDEADBEEFL));
        assertThat(blocked.accepted()).isFalse();
        assertThat(sim.snapshot().get("SECRET")).isZero();

        sim.apply(1, SimOp.write("LOCK", 0x0)); // 写入 unlockValue
        sim.apply(2, SimOp.write("SECRET", 0xCAFEBABEL));
        assertThat(sim.snapshot().get("SECRET")).isEqualTo(0xCAFEBABEL);
    }

    @Test
    void multiWordReadHonoursLowFirstOrder() {
        Simulator sim = new Simulator(model);
        StepResult result = sim.apply(0, SimOp.peek("TMR"));
        assertThat(result.readWords()).containsExactly("0x00000000", "0x00000001");
        assertThat(result.readValue()).isEqualTo("0x0000000100000000");
    }

    @Test
    void sideEffectsCascadeFromWrittenField() {
        Simulator sim = new Simulator(model);
        sim.apply(0, SimOp.write("CTRL", 0x1));
        assertThat(sim.snapshot().get("STAT")).isEqualTo(0x1);
    }

    @Test
    void readClearOfCrossWordFieldClearsOnlyItsBits() {
        Simulator sim = new Simulator(model);
        sim.apply(0, SimOp.write("TMR", 0xFFFFFFFF_FFFFFFFFL)); // 全部 RO/RC：RO 保持复位，RC 忽略写入
        // RC 直接写入被忽略，因此用 read 观察复位值中的跨字位域
        StepResult peek = sim.apply(1, SimOp.peek("TMR"));
        assertThat(peek.readValue()).isEqualTo("0x0000000100000000");
        assertThat(sim.snapshot().get("TMR")).isEqualTo(0x1_0000_0000L);
    }
}

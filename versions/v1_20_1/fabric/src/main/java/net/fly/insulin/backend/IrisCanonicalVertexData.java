package net.fly.insulin.backend;

import net.minecraft.core.Direction;

public final class IrisCanonicalVertexData {

    public static final int STRIDE = 40;
    public static final int COLOR_ALPHA_OFFSET = 11;
    public static final int NORMAL_PADDING_OFFSET = 31;
    public static final int BLOCK_ID_OFFSET = 32;
    public static final int UNKNOWN_BLOCK_STATE = 0x000f_ffff;
    private static final int DIRECTION_SHIFT = 4;
    private static final int DIRECTION_MASK = 0x7;
    private static final int SHADE_BIT = 0x80;
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private IrisCanonicalVertexData() {
    }

    public static void setBlockStateId(int id) {
        State state = STATE.get();
        state.blockStateId = id >= 0 && id < UNKNOWN_BLOCK_STATE ? id : UNKNOWN_BLOCK_STATE;
        state.direction = -1;
        state.shade = false;
    }

    public static void setLighting(Direction direction, boolean shade) {
        State state = STATE.get();
        state.direction = direction == null ? -1 : direction.get3DDataValue();
        state.shade = shade;
    }

    public static void reset() {
        STATE.get().reset();
    }

    public static int blockStateId() {
        return STATE.get().blockStateId;
    }

    public static byte metadata() {
        State state = STATE.get();
        int direction = state.direction < 0 ? DIRECTION_MASK : state.direction;
        return (byte) ((state.blockStateId >>> 16) & 0x0f
            | direction << DIRECTION_SHIFT
            | (state.shade ? SHADE_BIT : 0));
    }

    public static int stateId(byte metadata, short lowBits) {
        return (metadata & 0x0f) << 16 | lowBits & 0xffff;
    }

    public static Direction direction(byte metadata) {
        int ordinal = metadata >>> DIRECTION_SHIFT & DIRECTION_MASK;
        return ordinal < Direction.values().length ? Direction.from3DDataValue(ordinal) : null;
    }

    public static boolean shade(byte metadata) {
        return (metadata & SHADE_BIT) != 0;
    }

    private static final class State {
        private int blockStateId;
        private int direction;
        private boolean shade;

        private State() {
            this.reset();
        }

        private void reset() {
            this.blockStateId = UNKNOWN_BLOCK_STATE;
            this.direction = -1;
            this.shade = false;
        }
    }
}

package io.github.atlas2832.clairvoyance.client.render;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;

public class InstancedBlockRenderer {

    private static final int RECORD_SIZE = 12;
    private static final String SHADER_ROOT = "assets/clairvoyance/shaders/";

    private final DynamicInstancedBuffer buffer = new DynamicInstancedBuffer(RECORD_SIZE);
    private final float[] matrix = new float[16];
    private ShaderProgram program;
    private int mvpUniform;

    public void begin() {
        buffer.begin();
    }

    public void block(float x, float y, float z) {
        ByteBuffer record = buffer.record();
        record.putFloat(x);
        record.putFloat(y);
        record.putFloat(z);
    }

    public boolean isEmpty() {
        return buffer.getInstances() == 0;
    }

    public void draw(Matrix4f mvp) {
        if (buffer.getInstances() == 0) {
            return;
        }
        ensureInitialized();
        buffer.uploadAndBind();
        GL20.glUseProgram(program.getId());
        GL20.glUniformMatrix4fv(mvpUniform, false, mvp.get(matrix));
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 36, buffer.getInstances());
        GL20.glUseProgram(0);
        buffer.unbind();
    }

    private void ensureInitialized() {
        if (program != null) {
            return;
        }
        program = new ShaderProgram(
                SHADER_ROOT + "instanced-blocks.vsh",
                SHADER_ROOT + "instanced-blocks.fsh",
                "inOrigin");
        mvpUniform = program.getUniform("MVP");

        buffer.initialize();
        buffer.attribute(0, 3, GL11.GL_FLOAT, false, 0);
        buffer.finishInitialization();
    }
}

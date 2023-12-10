package org.vulkanb.eng.graph.shadows;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import org.vulkanb.eng.EngineProperties;
import org.vulkanb.eng.graph.*;
import org.vulkanb.eng.graph.geometry.GeometryAttachments;
import org.vulkanb.eng.graph.vk.*;
import org.vulkanb.eng.scene.Scene;

import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK11.*;

public class ShadowRenderActivity {

    private static final String SHADOW_FRAGMENT_SHADER_FILE_GLSL = "resources/shaders/shadow_fragment.glsl";
    private static final String SHADOW_FRAGMENT_SHADER_FILE_SPV = SHADOW_FRAGMENT_SHADER_FILE_GLSL + ".spv";
    private static final String SHADOW_GEOMETRY_SHADER_FILE_GLSL = "resources/shaders/shadow_geometry.glsl";
    private static final String SHADOW_GEOMETRY_SHADER_FILE_SPV = SHADOW_GEOMETRY_SHADER_FILE_GLSL + ".spv";
    private static final String SHADOW_VERTEX_SHADER_FILE_GLSL = "resources/shaders/shadow_vertex.glsl";
    private static final String SHADOW_VERTEX_SHADER_FILE_SPV = SHADOW_VERTEX_SHADER_FILE_GLSL + ".spv";

    private final Device device;
    private final Scene scene;
    private final ShadowsFrameBuffer shadowsFrameBuffer;
    private List<CascadeShadow> cascadeShadows;
    private DescriptorPool descriptorPool;
    private DescriptorSetLayout[] descriptorSetLayouts;
    private boolean firstRun;
    private DescriptorSet.StorageDescriptorSet materialsDescriptorSet;
    private Pipeline pipeLine;
    private DescriptorSet.UniformDescriptorSet[] projMatrixDescriptorSet;
    private ShaderProgram shaderProgram;
    private VulkanBuffer[] shadowsUniforms;
    private DescriptorSetLayout.StorageDescriptorSetLayout storageDescriptorSetLayout;
    private SwapChain swapChain;
    private TextureDescriptorSet textureDescriptorSet;
    private DescriptorSetLayout.SamplerDescriptorSetLayout textureDescriptorSetLayout;
    private TextureSampler textureSampler;
    private DescriptorSetLayout.UniformDescriptorSetLayout uniformDescriptorSetLayout;

    public ShadowRenderActivity(SwapChain swapChain, PipelineCache pipelineCache, Scene scene, GlobalBuffers globalBuffers) {
        firstRun = true;
        this.swapChain = swapChain;
        this.scene = scene;
        device = swapChain.getDevice();
        int numImages = swapChain.getNumImages();
        shadowsFrameBuffer = new ShadowsFrameBuffer(device);
        createShaders();
        createDescriptorPool(numImages);
        createDescriptorSets(numImages, globalBuffers);
        createPipeline(pipelineCache);
        createShadowCascades();
    }

    public void cleanup() {
        pipeLine.cleanup();
        Arrays.asList(shadowsUniforms).forEach(VulkanBuffer::cleanup);
        uniformDescriptorSetLayout.cleanup();
        textureDescriptorSetLayout.cleanup();
        storageDescriptorSetLayout.cleanup();
        textureSampler.cleanup();
        descriptorPool.cleanup();
        shaderProgram.cleanup();
        shadowsFrameBuffer.cleanup();
    }

    private void createDescriptorPool(int numImages) {
        EngineProperties engineProps = EngineProperties.getInstance();
        List<DescriptorPool.DescriptorTypeCount> descriptorTypeCounts = new ArrayList<>();
        descriptorTypeCounts.add(new DescriptorPool.DescriptorTypeCount(numImages, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER));
        descriptorTypeCounts.add(new DescriptorPool.DescriptorTypeCount(engineProps.getMaxTextures(), VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER));
        descriptorPool = new DescriptorPool(device, descriptorTypeCounts);
    }

    private void createDescriptorSets(int numImages, GlobalBuffers globalBuffers) {
        EngineProperties engineProperties = EngineProperties.getInstance();
        uniformDescriptorSetLayout = new DescriptorSetLayout.UniformDescriptorSetLayout(device, 0, VK_SHADER_STAGE_GEOMETRY_BIT);
        textureDescriptorSetLayout = new DescriptorSetLayout.SamplerDescriptorSetLayout(device, engineProperties.getMaxTextures(), 0, VK_SHADER_STAGE_FRAGMENT_BIT);
        storageDescriptorSetLayout = new DescriptorSetLayout.StorageDescriptorSetLayout(device, 0, VK_SHADER_STAGE_FRAGMENT_BIT);
        descriptorSetLayouts = new DescriptorSetLayout[]{
                uniformDescriptorSetLayout,
                textureDescriptorSetLayout,
                storageDescriptorSetLayout,
        };

        textureSampler = new TextureSampler(device, 1, false);
        projMatrixDescriptorSet = new DescriptorSet.UniformDescriptorSet[numImages];
        materialsDescriptorSet = new DescriptorSet.StorageDescriptorSet(descriptorPool, storageDescriptorSetLayout,
                globalBuffers.getMaterialsBuffer(), 0);
        shadowsUniforms = new VulkanBuffer[numImages];
        for (int i = 0; i < numImages; i++) {
            shadowsUniforms[i] = new VulkanBuffer(device, (long)
                    GraphConstants.MAT4X4_SIZE * GraphConstants.SHADOW_MAP_CASCADE_COUNT,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 0);
            projMatrixDescriptorSet[i] = new DescriptorSet.UniformDescriptorSet(descriptorPool, uniformDescriptorSetLayout,
                    shadowsUniforms[i], 0);
        }
    }

    private void createPipeline(PipelineCache pipelineCache) {
        Pipeline.PipeLineCreationInfo pipeLineCreationInfo = new Pipeline.PipeLineCreationInfo(
                shadowsFrameBuffer.getRenderPass().getVkRenderPass(), shaderProgram,
                GeometryAttachments.NUMBER_COLOR_ATTACHMENTS, true, true, 0,
                new InstancedVertexBufferStructure(), descriptorSetLayouts);
        pipeLine = new Pipeline(pipelineCache, pipeLineCreationInfo);
        pipeLineCreationInfo.cleanup();
    }

    private void createShaders() {
        EngineProperties engineProperties = EngineProperties.getInstance();
        if (engineProperties.isShaderRecompilation()) {
            ShaderCompiler.compileShaderIfChanged(SHADOW_VERTEX_SHADER_FILE_GLSL, Shaderc.shaderc_glsl_vertex_shader);
            ShaderCompiler.compileShaderIfChanged(SHADOW_GEOMETRY_SHADER_FILE_GLSL, Shaderc.shaderc_glsl_geometry_shader);
            ShaderCompiler.compileShaderIfChanged(SHADOW_FRAGMENT_SHADER_FILE_GLSL, Shaderc.shaderc_glsl_fragment_shader);
        }
        shaderProgram = new ShaderProgram(device, new ShaderProgram.ShaderModuleData[]
                {
                        new ShaderProgram.ShaderModuleData(VK_SHADER_STAGE_VERTEX_BIT, SHADOW_VERTEX_SHADER_FILE_SPV),
                        new ShaderProgram.ShaderModuleData(VK_SHADER_STAGE_GEOMETRY_BIT, SHADOW_GEOMETRY_SHADER_FILE_SPV),
                        new ShaderProgram.ShaderModuleData(VK_SHADER_STAGE_FRAGMENT_BIT, SHADOW_FRAGMENT_SHADER_FILE_SPV),
                });
    }

    private void createShadowCascades() {
        cascadeShadows = new ArrayList<>();
        for (int i = 0; i < GraphConstants.SHADOW_MAP_CASCADE_COUNT; i++) {
            CascadeShadow cascadeShadow = new CascadeShadow();
            cascadeShadows.add(cascadeShadow);
        }
    }

    public Attachment getDepthAttachment() {
        return shadowsFrameBuffer.getDepthAttachment();
    }

    public List<CascadeShadow> getShadowCascades() {
        return cascadeShadows;
    }

    public void loadModels(TextureCache textureCache) {
        device.waitIdle();
        // Size of the descriptor is setup in the layout, we need to fill up the texture list
        // up to the number defined in the layout (reusing last texture)
        List<Texture> textureCacheList = textureCache.getAsList();
        int textureCacheSize = textureCacheList.size();
        List<Texture> textureList = new ArrayList<>(textureCacheList);
        EngineProperties engineProperties = EngineProperties.getInstance();
        int maxTextures = engineProperties.getMaxTextures();
        for (int i = 0; i < maxTextures - textureCacheSize; i++) {
            textureList.add(textureCacheList.get(textureCacheSize - 1));
        }
        textureDescriptorSet = new TextureDescriptorSet(descriptorPool, textureDescriptorSetLayout, textureList,
                textureSampler, 0);
    }

    public void recordCommandBuffer(CommandBuffer commandBuffer, GlobalBuffers globalBuffers, int idx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
            clearValues.apply(0, v -> v.depthStencil().depth(1.0f));

            EngineProperties engineProperties = EngineProperties.getInstance();
            int shadowMapSize = engineProperties.getShadowMapSize();
            int width = shadowMapSize;
            int height = shadowMapSize;

            VkCommandBuffer cmdHandle = commandBuffer.getVkCommandBuffer();

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0)
                    .y(height)
                    .height(-height)
                    .width(width)
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            vkCmdSetViewport(cmdHandle, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack)
                    .extent(it -> it
                            .width(width)
                            .height(height))
                    .offset(it -> it
                            .x(0)
                            .y(0));
            vkCmdSetScissor(cmdHandle, 0, scissor);

            FrameBuffer frameBuffer = shadowsFrameBuffer.getFrameBuffer();

            VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(shadowsFrameBuffer.getRenderPass().getVkRenderPass())
                    .pClearValues(clearValues)
                    .renderArea(a -> a.extent().set(width, height))
                    .framebuffer(frameBuffer.getVkFrameBuffer());

            vkCmdBeginRenderPass(cmdHandle, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);

            vkCmdBindPipeline(cmdHandle, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLine.getVkPipeline());

            LongBuffer descriptorSets = stack.mallocLong(3)
                    .put(0, projMatrixDescriptorSet[idx].getVkDescriptorSet())
                    .put(1, textureDescriptorSet.getVkDescriptorSet())
                    .put(2, materialsDescriptorSet.getVkDescriptorSet());

            vkCmdBindDescriptorSets(cmdHandle, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeLine.getVkPipelineLayout(), 0, descriptorSets, null);

            LongBuffer vertexBuffer = stack.mallocLong(1);
            LongBuffer instanceBuffer = stack.mallocLong(1);
            LongBuffer offsets = stack.mallocLong(1).put(0, 0L);

            // Draw commands for non animated models
            if (globalBuffers.getNumIndirectCommands() > 0) {
                vertexBuffer.put(0, globalBuffers.getVerticesBuffer().getBuffer());
                instanceBuffer.put(0, globalBuffers.getInstanceDataBuffers()[idx].getBuffer());

                vkCmdBindVertexBuffers(cmdHandle, 0, vertexBuffer, offsets);
                vkCmdBindVertexBuffers(cmdHandle, 1, instanceBuffer, offsets);
                vkCmdBindIndexBuffer(cmdHandle, globalBuffers.getIndicesBuffer().getBuffer(), 0, VK_INDEX_TYPE_UINT32);

                VulkanBuffer indirectBuffer = globalBuffers.getIndirectBuffer();
                vkCmdDrawIndexedIndirect(cmdHandle, indirectBuffer.getBuffer(), 0, globalBuffers.getNumIndirectCommands(),
                        GlobalBuffers.IND_COMMAND_STRIDE);
            }

            if (globalBuffers.getNumAnimIndirectCommands() > 0) {
                // Draw commands for  animated models
                vertexBuffer.put(0, globalBuffers.getAnimVerticesBuffer().getBuffer());
                instanceBuffer.put(0, globalBuffers.getAnimInstanceDataBuffers()[idx].getBuffer());

                vkCmdBindVertexBuffers(cmdHandle, 0, vertexBuffer, offsets);
                vkCmdBindVertexBuffers(cmdHandle, 1, instanceBuffer, offsets);
                vkCmdBindIndexBuffer(cmdHandle, globalBuffers.getIndicesBuffer().getBuffer(), 0, VK_INDEX_TYPE_UINT32);
                VulkanBuffer animIndirectBuffer = globalBuffers.getAnimIndirectBuffer();
                vkCmdDrawIndexedIndirect(cmdHandle, animIndirectBuffer.getBuffer(), 0, globalBuffers.getNumAnimIndirectCommands(),
                        GlobalBuffers.IND_COMMAND_STRIDE);
            }

            vkCmdEndRenderPass(cmdHandle);
        }
    }

    public void render() {
        if (firstRun || scene.isLightChanged() || scene.getCamera().isHasMoved()) {
            CascadeShadow.updateCascadeShadows(cascadeShadows, scene);
            if (firstRun) {
                firstRun = false;
            }
        }

        int idx = swapChain.getCurrentFrame();
        int offset = 0;
        for (CascadeShadow cascadeShadow : cascadeShadows) {
            VulkanUtils.copyMatrixToBuffer(shadowsUniforms[idx], cascadeShadow.getProjViewMatrix(), offset);
            offset += GraphConstants.MAT4X4_SIZE;
        }
    }

    public void resize(SwapChain swapChain) {
        this.swapChain = swapChain;
        CascadeShadow.updateCascadeShadows(cascadeShadows, scene);
    }
}
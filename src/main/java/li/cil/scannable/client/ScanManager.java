package li.cil.scannable.client;

import li.cil.scannable.api.scanning.ScanResult;
import li.cil.scannable.api.scanning.ScanResultProvider;
import li.cil.scannable.client.renderer.ScannerRenderer;
import li.cil.scannable.common.capabilities.CapabilityScanResultProvider;
import li.cil.scannable.common.config.Constants;
import li.cil.scannable.common.config.Settings;
import li.cil.scannable.common.init.Items;
import li.cil.scannable.integration.optifine.ProxyOptiFine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;

import javax.annotation.Nullable;
import java.util.*;

@SideOnly(Side.CLIENT)
public enum ScanManager {
    INSTANCE;

    private final FloatBuffer savedMv = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer savedProj = BufferUtils.createFloatBuffer(16);

    // --------------------------------------------------------------------- //

    private static int computeTargetRadius() {
        return Minecraft.getMinecraft().gameSettings.renderDistanceChunks * Constants.CHUNK_SIZE - Constants.SCAN_INITIAL_RADIUS;
    }

    public static int computeScanGrowthDuration() {
        return Constants.SCAN_GROWTH_DURATION * Minecraft.getMinecraft().gameSettings.renderDistanceChunks / Constants.REFERENCE_RENDER_DISTANCE;
    }

    public static float computeRadius(final long start, final float duration) {
        // Scan wave speeds up exponentially. To avoid the initial speed being
        // near zero due to that we offset the time and adjust the remaining
        // parameters accordingly. Base equation is:
        //   r = a + (t + b)^2 * c
        // with r := 0 and target radius and t := 0 and target time this yields:
        //   c = r1/((t1 + b)^2 - b*b)
        //   a = -r1*b*b/((t1 + b)^2 - b*b)

        final float r1 = (float) computeTargetRadius();
        final float t1 = duration;
        final float b = Constants.SCAN_TIME_OFFSET;
        final float n = 1f / ((t1 + b) * (t1 + b) - b * b);
        final float a = -r1 * b * b * n;
        final float c = r1 * n;

        final float t = (float) (System.currentTimeMillis() - start);

        return Constants.SCAN_INITIAL_RADIUS + a + (t + b) * (t + b) * c;
    }

    // --------------------------------------------------------------------- //

    // List of providers currently used to scan.
    private final Set<ScanResultProvider> collectingProviders = new HashSet<>();
    // List for collecting results during an active scan.
    private final Map<ScanResultProvider, List<ScanResult>> collectingResults = new HashMap<>();

    // Results get copied from the collectingResults list in here when a scan
    // completes. This is to avoid clearing active results by *starting* a scan.
    private final Map<ScanResultProvider, List<ScanResult>> pendingResults = new HashMap<>();
    private final Map<ScanResultProvider, List<ScanResult>> renderingResults = new HashMap<>();
    // Temporary, re-used list to collect visible results each frame.
    private final List<ScanResult> renderingList = new ArrayList<>();

    private int scanningTicks = -1;
    private long currentStart = -1;
    @Nullable
    private Vec3d lastScanCenter;

    // --------------------------------------------------------------------- //

    public void beginScan(final EntityPlayer player, final List<ItemStack> modules) {
        cancelScan();

        float scanRadius = Settings.getBaseScanRadius();

        for (final ItemStack module : modules) {
            final ScanResultProvider provider = module.getCapability(CapabilityScanResultProvider.SCAN_RESULT_PROVIDER_CAPABILITY, null);
            if (provider != null) {
                collectingProviders.add(provider);
            }

            if (Items.isModuleRange(module)) {
                scanRadius += MathHelper.ceil(Settings.getBaseScanRadius() / 2f);
            }
        }

        if (collectingProviders.isEmpty()) {
            return;
        }

        final Vec3d center = player.getPositionVector();
        for (final ScanResultProvider provider : collectingProviders) {
            provider.initialize(player, modules, center, scanRadius, Constants.SCAN_COMPUTE_DURATION);
        }
    }

    public void updateScan(final Entity entity, final boolean finish) {
        final int remaining = Constants.SCAN_COMPUTE_DURATION - scanningTicks;

        if (!finish) {
            if (remaining <= 0) {
                return;
            }

            for (final ScanResultProvider provider : collectingProviders) {
                provider.computeScanResults(result -> collectingResults.computeIfAbsent(provider, p -> new ArrayList<>()).add(result));
            }

            ++scanningTicks;

            return;
        }

        for (int i = 0; i < remaining; i++) {
            for (final ScanResultProvider provider : collectingProviders) {
                provider.computeScanResults(result -> collectingResults.computeIfAbsent(provider, p -> new ArrayList<>()).add(result));
            }
        }

        for (final ScanResultProvider provider : collectingProviders) {
            provider.reset();
        }

        clear();

        lastScanCenter = entity.getPositionVector();
        currentStart = System.currentTimeMillis();

        pendingResults.putAll(collectingResults);
        pendingResults.values().forEach(list -> list.sort(Comparator.comparing(result -> -lastScanCenter.distanceTo(result.getPosition()))));

        ScannerRenderer.INSTANCE.ping(lastScanCenter);

        cancelScan();
    }

    public void cancelScan() {
        collectingProviders.clear();
        collectingResults.clear();
        scanningTicks = 0;
    }

    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (lastScanCenter == null || currentStart < 0) {
            return;
        }

        if (Constants.SCAN_STAY_DURATION < (int) (System.currentTimeMillis() - currentStart)) {
            pendingResults.clear();
            synchronized (renderingResults) {
                if (!renderingResults.isEmpty()) {
                    for (Iterator<Map.Entry<ScanResultProvider, List<ScanResult>>> iterator = renderingResults.entrySet().iterator(); iterator.hasNext(); ) {
                        final Map.Entry<ScanResultProvider, List<ScanResult>> entry = iterator.next();
                        final List<ScanResult> list = entry.getValue();
                        for (int i = MathHelper.ceil(list.size() / 2f); i > 0; i--) {
                            list.remove(list.size() - 1);
                        }
                        if (list.isEmpty()) {
                            iterator.remove();
                        }
                    }
                }

                if (renderingResults.isEmpty()) {
                    clear();
                }
            }
            return;
        }

        if (pendingResults.size() <= 0) {
            return;
        }

        final float radius = computeRadius(currentStart, computeScanGrowthDuration());
        final float sqRadius = radius * radius;

        final Iterator<Map.Entry<ScanResultProvider, List<ScanResult>>> iterator = pendingResults.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<ScanResultProvider, List<ScanResult>> entry = iterator.next();
            final ScanResultProvider provider = entry.getKey();
            final List<ScanResult> results = entry.getValue();

            while (results.size() > 0) {
                final ScanResult result = results.get(results.size() - 1);
                final Vec3d position = result.getPosition();
                if (lastScanCenter.squareDistanceTo(position) <= sqRadius) {
                    results.remove(results.size() - 1);
                    if (!provider.isValid(result)) {
                        continue;
                    }
                    synchronized (renderingResults) {
                        renderingResults.computeIfAbsent(provider, p -> new ArrayList<>()).add(result);
                    }
                } else {
                    break; // List is sorted, so nothing else is in range.
                }
            }

            if (results.size() == 0) {
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public void onRenderLast(final RenderWorldLastEvent event) {
        final boolean isUsingShaders = ProxyOptiFine.INSTANCE.isShaderPackLoaded();
        if (isUsingShaders) {
            getMatrixRaw(GL11.GL_PROJECTION_MATRIX, savedProj);
            getMatrixRaw(GL11.GL_MODELVIEW_MATRIX, savedMv);
            return;
        }

        synchronized (renderingResults) {
            if (renderingResults.isEmpty()) {
                return;
            }

            render(event.getPartialTicks());
        }
    }

    @SubscribeEvent
    public void onPreRenderGameOverlay(final RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }

        final boolean isUsingShaders = ProxyOptiFine.INSTANCE.isShaderPackLoaded();
        if (!isUsingShaders) {
            return;
        }

        synchronized (renderingResults) {
            if (renderingResults.isEmpty()) {
                return;
            }

            // Using shaders so we render as game overlay; restore matrices as used for world rendering.
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.multMatrix(savedProj);
            savedProj.position(0);
                
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.multMatrix(savedMv);
            savedMv.position(0);

            //Minecraft.getMinecraft().entityRenderer.setupCameraTransform(event.getPartialTicks(), 2);
            render(event.getPartialTicks());

            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
        }
    }

    private void render(final float partialTicks) {
        final Minecraft mc = Minecraft.getMinecraft();

        final Entity entity = mc.getRenderViewEntity();
        if (entity == null) {
            return;
        }

        final ICamera frustum = new Frustum();
        final double posX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        final double posY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        final double posZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        frustum.setPosition(posX, posY, posZ);

        GlStateManager.bindTexture(0);
        GlStateManager.color(1, 1, 1, 1);

        // We render all results in batches, grouped by their provider.
        // This allows providers to do more optimized rendering, in e.g.
        // setting up the render state once before rendering all visuals,
        // or even set up display lists or VBOs.
        for (final Map.Entry<ScanResultProvider, List<ScanResult>> entry : renderingResults.entrySet()) {
            // Quick and dirty frustum culling.
            for (ScanResult result : entry.getValue()) {
                final AxisAlignedBB bounds = result.getRenderBounds();
                if (bounds == null || frustum.isBoundingBoxInFrustum(bounds)) {
                    renderingList.add(result);
                }
            }

            if (!renderingList.isEmpty()) {
                entry.getKey().render(entity, renderingList, partialTicks);
                renderingList.clear();
            }
        }
    }

    // --------------------------------------------------------------------- //

    private void clear() {
        pendingResults.clear();

        synchronized (renderingResults) {
            renderingResults.forEach((provider, results) -> provider.reset());
            renderingResults.clear();
        }

        lastScanCenter = null;
        currentStart = -1;
    }

    private void getMatrixRaw(final int matrix, final FloatBuffer into) {
        into.position(0);
        GlStateManager.getFloat(matrix, into);
        into.position(0);
    }


}

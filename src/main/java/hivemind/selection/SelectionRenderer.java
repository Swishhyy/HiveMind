package hivemind.selection;

import hivemind.HiveMind;
import hivemind.api.event.events.RenderEvent;
import hivemind.api.event.listener.AbstractGameEventListener;
import hivemind.api.selection.ISelection;
import hivemind.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;

public class SelectionRenderer implements IRenderer, AbstractGameEventListener {

    public static final double SELECTION_BOX_EXPANSION = .005D;

    private final SelectionManager manager;

    SelectionRenderer(HiveMind hivemind, SelectionManager manager) {
        this.manager = manager;
        hivemind.getGameEventHandler().registerEventListener(this);
    }

    public static void renderSelections(PoseStack stack, ISelection[] selections) {
        float opacity = settings.selectionOpacity.value;
        boolean ignoreDepth = settings.renderSelectionIgnoreDepth.value;
        float lineWidth = settings.selectionLineWidth.value;

        if (!settings.renderSelection.value || selections.length == 0) {
            return;
        }

        BufferBuilder bufferBuilder = IRenderer.startLines(settings.colorSelection.value, opacity);

        for (ISelection selection : selections) {
            IRenderer.emitAABB(bufferBuilder, stack, selection.aabb(), SELECTION_BOX_EXPANSION, lineWidth);
        }

        if (settings.renderSelectionCorners.value) {
            IRenderer.glColor(settings.colorSelectionPos1.value, opacity);

            for (ISelection selection : selections) {
                IRenderer.emitAABB(bufferBuilder, stack, new AABB(selection.pos1()), lineWidth);
            }

            IRenderer.glColor(settings.colorSelectionPos2.value, opacity);

            for (ISelection selection : selections) {
                IRenderer.emitAABB(bufferBuilder, stack, new AABB(selection.pos2()), lineWidth);
            }
        }

        IRenderer.endLines(bufferBuilder, ignoreDepth);
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        renderSelections(event.getModelViewStack(), manager.getSelections());
    }
}

package org.ingamewiki.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.fml.ModList;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.util.*;

public class WikiScreen extends Screen {
    private EditBox input;
    private final List<String> results = new ArrayList<>();
    private List<FormattedCharSequence> lines = List.of();
    private List<CraftingRecipe> recipes = List.of();
    private String article = "", title = "", status = "输入关键词并搜索", imageStatus = "";
    private int offset = 0, generation = 0, imageWidth, imageHeight, recipeIndex;
    private boolean reading = false, itemMatched = false;
    private ResourceLocation imageTexture;
    public WikiScreen() { super(Component.literal("游戏内 Wiki")); }
    @Override protected void init() {
        int left = Math.max(10, (width - 620) / 2), right = width - left;
        String value = input == null ? "" : input.getValue();
        input = new EditBox(font, left, 34, Math.max(60, right-left-78), 20, Component.literal("搜索 Wiki"));
        input.setMaxLength(100); input.setValue(value); addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> search()).bounds(right-72, 34, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回结果"), b -> { generation++; releaseImage(); reading=false; offset=0; status=results.isEmpty()?"暂无搜索结果":"点击条目阅读"; }).bounds(left, height-28, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(right-60, height-28, 60, 20).build());
        if (reading) wrap();
    }
    private void search() {
        String query = input.getValue().trim();
        if (query.isEmpty()) { status="请输入关键词"; return; }
        int request = ++generation; releaseImage(); reading=false; results.clear(); offset=0; status="正在搜索…";
        WikiClient.search(query).whenComplete((items, error) -> minecraft.execute(() -> {
            if (request != generation || minecraft.screen != this) return;
            if (error != null) { status="搜索失败，请检查网络后重试"; return; }
            results.addAll(items); status=results.isEmpty()?"没有找到结果":"点击条目阅读";
        }));
    }
    private void open(String name) {
        int request=++generation; releaseImage(); title=name; reading=true; article=""; lines=List.of(); offset=0;
        status="正文加载中…"; imageStatus="图片加载中…"; recipeIndex=0; findRecipes(name);
        WikiClient.article(name).whenComplete((text, error) -> minecraft.execute(() -> {
            if (request != generation || minecraft.screen != this) return;
            if (error != null) { status="正文加载失败，请检查网络后重试"; return; }
            article=text; status="滚轮翻阅 · 图片和正文来自中文 Minecraft Wiki"; wrap();
        }));
        WikiClient.thumbnail(name).thenCompose(image -> image == null ? java.util.concurrent.CompletableFuture.completedFuture(null) :
            WikiClient.image(image).thenApply(bytes -> bytes)).whenComplete((bytes, error) -> minecraft.execute(() -> {
            if (request != generation || minecraft.screen != this) return;
            if (error != null) { imageStatus="主图加载失败"; return; }
            if (bytes == null) { imageStatus="此条目没有主图"; return; }
            try {
                // Check dimensions before allocating the native pixel buffer.
                try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(stream);
                    if (!readers.hasNext()) { imageStatus="主图格式不受支持"; return; }
                    var reader = readers.next();
                    try {
                        reader.setInput(stream, true, true);
                        if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048) { imageStatus="图片尺寸过大"; return; }
                    } finally { reader.dispose(); }
                }
                NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(bytes));
                imageWidth=nativeImage.getWidth(); imageHeight=nativeImage.getHeight();
                if (imageWidth < 1 || imageHeight < 1 || imageWidth > 2048 || imageHeight > 2048) { nativeImage.close(); imageStatus="图片尺寸过大"; return; }
                imageTexture=minecraft.getTextureManager().register("ingamewiki", new DynamicTexture(nativeImage));
                imageStatus="";
            } catch (Exception e) { imageStatus="主图格式不受支持"; }
        }));
    }
    private void findRecipes(String name) {
        recipes=List.of(); itemMatched=false;
        if (minecraft.level == null) return;
        List<Item> matches = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (stack.getHoverName().getString().equalsIgnoreCase(name) || (id != null && (id.toString().equalsIgnoreCase(name) || id.getPath().equalsIgnoreCase(name.replace(' ', '_'))))) matches.add(item);
        }
        // Do not guess when multiple registered items have the same translated name.
        if (matches.size()!=1) return;
        itemMatched=true; Item target=matches.get(0);
        recipes=minecraft.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
            .filter(r -> (r instanceof ShapedRecipe || r instanceof ShapelessRecipe) && r.getResultItem(minecraft.level.registryAccess()).is(target))
            .sorted(Comparator.comparing(r -> r.getId().toString())).toList();
    }
    private void releaseImage() { if (imageTexture != null && minecraft != null) { minecraft.getTextureManager().release(imageTexture); imageTexture=null; } }
    @Override public void removed() { generation++; releaseImage(); super.removed(); }
    private void wrap() {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String line : article.split("\n", -1)) {
            if (line.isBlank()) wrapped.add(FormattedCharSequence.EMPTY);
            else wrapped.addAll(font.split(Component.literal(line), Math.max(40, Math.min(620, width-20)-12)));
        }
        lines=wrapped; offset=Math.min(offset, maxOffset());
    }
    private int imageDrawHeight() { return imageTexture == null ? 14 : Math.max(1,(int)(imageHeight * Math.min(1.0, Math.min(120.0/imageWidth, 90.0/imageHeight)))); }
    private int headerHeight() { return Math.max(imageDrawHeight()+20, recipes.isEmpty()?45:100); }
    private int maxOffset() { return Math.max(0, (reading ? headerHeight()+lines.size()*11 : results.size()*20) - Math.max(1,height-110)); }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g); int left=Math.max(10,(width-620)/2), right=width-left;
        g.drawString(font, "游戏内 Wiki" + (reading ? " / " + title : ""), left, 15, 0xFFFFFF);
        g.drawString(font, status, left, 61, 0xAAAAAA);
        g.enableScissor(left, 76, right, height-32);
        ItemStack hovered=ItemStack.EMPTY;
        if (reading) {
            int y=78-offset; int h=imageDrawHeight();
            if (imageTexture != null) {
                int w=Math.max(1,(int)(imageWidth * Math.min(1.0, Math.min(120.0/imageWidth,90.0/imageHeight))));
                g.blit(imageTexture, left+3, y, w,h, 0,0,imageWidth,imageHeight,imageWidth,imageHeight);
            } else g.drawString(font, imageStatus, left+3,y,0xAAAAAA);
            int rx=left+145;
            if (rx+145 < right) {
                if (!itemMatched) g.drawString(font, "未匹配到当前语言的游戏物品",rx,y,0xAAAAAA);
                else if (recipes.isEmpty()) g.drawString(font,"没有可显示的普通合成配方",rx,y,0xAAAAAA);
                else hovered=drawRecipe(g,rx,y,mouseX,mouseY);
            } else g.drawString(font,"窗口较窄，配方暂不可显示",left+3,y+h+3,0xAAAAAA);
            int body=78+headerHeight()-offset;
            for(int i=0;i<lines.size();i++) { int row=body+i*11; if(row>=65 && row<height-30) g.drawString(font,lines.get(i),left+3,row,0xEEEEEE,false); }
        } else {
            for(int i=0;i<results.size();i++) { int y=78+i*20-offset; if(y<60||y>=height-30) continue;
                if(mouseX>=left && mouseX<right && mouseY>=y && mouseY<y+18) g.fill(left,y-2,right,y+16,0x66444444);
                g.drawString(font,results.get(i),left+3,y,0xAADDFF);
            }
        }
        g.disableScissor(); super.render(g,mouseX,mouseY,partialTick);
        if (!hovered.isEmpty() && mouseY>=76 && mouseY<height-32) g.renderTooltip(font,hovered,mouseX,mouseY);
    }
    private ItemStack drawRecipe(GuiGraphics g,int x,int y,int mx,int my) {
        CraftingRecipe recipe=recipes.get(recipeIndex);
        g.drawString(font,"当前游戏合成配方 " + (recipeIndex+1) + "/" + recipes.size() + (recipes.size()>1?"  点击切换":""),x,y,0xFFFFAA);
        int w=recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeWidth() : 3;
        var ingredients=recipe.getIngredients(); ItemStack hover=ItemStack.EMPTY;
        for(int i=0;i<ingredients.size() && i<9;i++) {
            int col=i%w, row=i/w; if(col>2 || row>2) continue;
            int sx=x+col*19, sy=y+14+row*19; g.fill(sx,sy,sx+18,sy+18,0xAA555555);
            ItemStack[] variants=ingredients.get(i).getItems();
            if(variants.length>0) { ItemStack stack=variants[(int)((System.currentTimeMillis()/1500)%variants.length)]; g.renderItem(stack,sx+1,sy+1); if(mx>=sx && mx<sx+18 && my>=sy && my<sy+18) hover=stack; }
        }
        g.drawString(font,"→",x+64,y+34,0xFFFFFF);
        ItemStack output=recipe.getResultItem(minecraft.level.registryAccess()); int ox=x+82, oy=y+33;
        g.fill(ox-1,oy-1,ox+17,oy+17,0xAA555555); g.renderItem(output,ox,oy); g.renderItemDecorations(font,output,ox,oy);
        if(mx>=ox && mx<ox+16 && my>=oy && my<oy+16) hover=output;
        return hover;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        int left=Math.max(10,(width-620)/2), right=width-left;
        if (reading && button==0 && recipes.size()>1 && x>=left+145 && x<right && y>=78-offset && y<78-offset+75 && y>=76 && y<height-32) { recipeIndex=(recipeIndex+1)%recipes.size(); return true; }
        if (!reading && button==0 && x>=left && x<right && y>=78 && y<height-35) {
            int index=(int)((y-78+offset)/20); if(index>=0 && index<results.size()) { open(results.get(index)); return true; }
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) { offset=Math.max(0,Math.min(maxOffset(),offset-(int)Math.signum(delta)*33)); return true; }
    @Override public boolean keyPressed(int key, int scan, int mods) { if (super.keyPressed(key,scan,mods)) return true; if ((key==257 || key==335) && input.isFocused() && !ModList.get().isLoaded("caramelchat")) { search(); return true; } return false; }
    @Override public boolean isPauseScreen() { return false; }
}

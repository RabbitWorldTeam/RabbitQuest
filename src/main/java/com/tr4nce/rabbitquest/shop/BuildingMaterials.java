package com.tr4nce.rabbitquest.shop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/** The vanilla 1.21.1 building catalog used by the materials preset. */
final class BuildingMaterials {
    static final int UNITS_PER_VOUCHER = 640;

    private record Color(String id, String name) {}
    record Entry(String id, String name) {
        Item item() {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(id)).orElseThrow();
        }
    }
    record Category(String id, String name, List<Entry> entries) {}

    private static final List<Color> COLORS = List.of(
            new Color("white", "白色"),
            new Color("light_gray", "淡灰色"),
            new Color("gray", "灰色"),
            new Color("black", "黑色"),
            new Color("brown", "棕色"),
            new Color("red", "红色"),
            new Color("orange", "橙色"),
            new Color("yellow", "黄色"),
            new Color("lime", "黄绿色"),
            new Color("green", "绿色"),
            new Color("cyan", "青色"),
            new Color("light_blue", "淡蓝色"),
            new Color("blue", "蓝色"),
            new Color("purple", "紫色"),
            new Color("magenta", "品红色"),
            new Color("pink", "粉红色")
    );

    static final List<Category> CATEGORIES = List.of(
            new Category("concrete", "混凝土", colored("concrete", "混凝土")),
            new Category("terracotta", "陶瓦", colored("terracotta", "陶瓦", new Entry("terracotta", "陶瓦"))),
            new Category("wool", "羊毛", colored("wool", "羊毛")),
            new Category("glass", "玻璃", colored("stained_glass", "染色玻璃",
                    new Entry("glass", "玻璃"), new Entry("tinted_glass", "遮光玻璃"))),
            new Category("stone", "石料", List.of(
                    new Entry("stone", "石头"),
                    new Entry("smooth_stone", "平滑石头"),
                    new Entry("cobblestone", "圆石"),
                    new Entry("mossy_cobblestone", "苔石"),
                    new Entry("stone_bricks", "石砖"),
                    new Entry("mossy_stone_bricks", "苔石砖"),
                    new Entry("cracked_stone_bricks", "裂纹石砖"),
                    new Entry("chiseled_stone_bricks", "錾制石砖"),
                    new Entry("granite", "花岗岩"),
                    new Entry("polished_granite", "磨制花岗岩"),
                    new Entry("diorite", "闪长岩"),
                    new Entry("polished_diorite", "磨制闪长岩"),
                    new Entry("andesite", "安山岩"),
                    new Entry("polished_andesite", "磨制安山岩"),
                    new Entry("deepslate", "深板岩"),
                    new Entry("cobbled_deepslate", "深板岩圆石"),
                    new Entry("polished_deepslate", "磨制深板岩"),
                    new Entry("deepslate_bricks", "深板岩砖"),
                    new Entry("cracked_deepslate_bricks", "裂纹深板岩砖"),
                    new Entry("deepslate_tiles", "深板岩瓦"),
                    new Entry("cracked_deepslate_tiles", "裂纹深板岩瓦"),
                    new Entry("chiseled_deepslate", "錾制深板岩"),
                    new Entry("tuff", "凝灰岩"),
                    new Entry("polished_tuff", "磨制凝灰岩"),
                    new Entry("tuff_bricks", "凝灰岩砖"),
                    new Entry("chiseled_tuff", "錾制凝灰岩"),
                    new Entry("chiseled_tuff_bricks", "錾制凝灰岩砖"),
                    new Entry("calcite", "方解石"),
                    new Entry("dripstone_block", "滴水石块"),
                    new Entry("sandstone", "砂岩"),
                    new Entry("cut_sandstone", "切制砂岩"),
                    new Entry("smooth_sandstone", "平滑砂岩"),
                    new Entry("chiseled_sandstone", "錾制砂岩"),
                    new Entry("red_sandstone", "红砂岩"),
                    new Entry("cut_red_sandstone", "切制红砂岩"),
                    new Entry("smooth_red_sandstone", "平滑红砂岩"),
                    new Entry("chiseled_red_sandstone", "錾制红砂岩"),
                    new Entry("blackstone", "黑石"),
                    new Entry("polished_blackstone", "磨制黑石"),
                    new Entry("polished_blackstone_bricks", "磨制黑石砖"),
                    new Entry("cracked_polished_blackstone_bricks", "裂纹磨制黑石砖"),
                    new Entry("chiseled_polished_blackstone", "錾制磨制黑石"),
                    new Entry("basalt", "玄武岩"),
                    new Entry("smooth_basalt", "平滑玄武岩"),
                    new Entry("polished_basalt", "磨制玄武岩"),
                    new Entry("netherrack", "下界岩"),
                    new Entry("nether_bricks", "下界砖块"),
                    new Entry("red_nether_bricks", "红色下界砖块"),
                    new Entry("cracked_nether_bricks", "裂纹下界砖块"),
                    new Entry("chiseled_nether_bricks", "錾制下界砖块"),
                    new Entry("end_stone", "末地石"),
                    new Entry("end_stone_bricks", "末地石砖"),
                    new Entry("purpur_block", "紫珀块"),
                    new Entry("purpur_pillar", "紫珀柱"),
                    new Entry("quartz_block", "石英块"),
                    new Entry("smooth_quartz", "平滑石英块"),
                    new Entry("quartz_bricks", "石英砖"),
                    new Entry("chiseled_quartz_block", "錾制石英块"),
                    new Entry("quartz_pillar", "石英柱"),
                    new Entry("prismarine", "海晶石"),
                    new Entry("prismarine_bricks", "海晶石砖"),
                    new Entry("dark_prismarine", "暗海晶石"),
                    new Entry("bricks", "红砖块"),
                    new Entry("mud_bricks", "泥砖")
            )),
            new Category("logs", "原木与菌柄", logs()),
            new Category("lighting", "照明", lights())
    );

    private BuildingMaterials() {}

    private static List<Entry> colored(String suffix, String name, Entry... extras) {
        List<Entry> entries = new ArrayList<>(List.of(extras));
        for (Color color : COLORS) {
            entries.add(new Entry(color.id() + "_" + suffix, color.name() + name));
        }
        return List.copyOf(entries);
    }

    private static List<Entry> logs() {
        List<Entry> natural = List.of(
                new Entry("oak_log", "橡木原木"),
                new Entry("spruce_log", "云杉原木"),
                new Entry("birch_log", "白桦原木"),
                new Entry("jungle_log", "丛林原木"),
                new Entry("acacia_log", "金合欢原木"),
                new Entry("dark_oak_log", "深色橡木原木"),
                new Entry("mangrove_log", "红树原木"),
                new Entry("cherry_log", "樱花原木"),
                new Entry("crimson_stem", "绯红菌柄"),
                new Entry("warped_stem", "诡异菌柄"),
                new Entry("bamboo_block", "竹块")
        );
        List<Entry> entries = new ArrayList<>(natural);
        for (Entry log : natural) {
            entries.add(new Entry("stripped_" + log.id(), "去皮" + log.name()));
        }
        return List.copyOf(entries);
    }

    private static List<Entry> lights() {
        List<Entry> entries = new ArrayList<>(List.of(
                new Entry("torch", "火把"),
                new Entry("soul_torch", "灵魂火把"),
                new Entry("lantern", "灯笼"),
                new Entry("soul_lantern", "灵魂灯笼"),
                new Entry("glowstone", "荧石"),
                new Entry("sea_lantern", "海晶灯"),
                new Entry("end_rod", "末地烛"),
                new Entry("shroomlight", "菌光体"),
                new Entry("ochre_froglight", "赭黄蛙明灯"),
                new Entry("verdant_froglight", "翠绿蛙明灯"),
                new Entry("pearlescent_froglight", "珠紫蛙明灯"),
                new Entry("jack_o_lantern", "南瓜灯"),
                new Entry("redstone_lamp", "红石灯"),
                new Entry("redstone_torch", "红石火把"),
                new Entry("campfire", "营火"),
                new Entry("soul_campfire", "灵魂营火"),
                new Entry("sea_pickle", "海泡菜"),
                new Entry("glow_lichen", "发光地衣"),
                new Entry("magma_block", "岩浆块")
        ));
        List<Entry> bulbs = List.of(
                new Entry("copper_bulb", "铜灯"),
                new Entry("exposed_copper_bulb", "斑驳的铜灯"),
                new Entry("weathered_copper_bulb", "锈蚀的铜灯"),
                new Entry("oxidized_copper_bulb", "氧化的铜灯")
        );
        entries.addAll(bulbs);
        for (Entry bulb : bulbs) {
            entries.add(new Entry("waxed_" + bulb.id(), "涂蜡" + bulb.name()));
        }
        entries.addAll(colored("candle", "蜡烛", new Entry("candle", "蜡烛")));
        return List.copyOf(entries);
    }
}

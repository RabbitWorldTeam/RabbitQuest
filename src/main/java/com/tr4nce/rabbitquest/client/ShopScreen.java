package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.client.ShopDraft.Section;
import com.tr4nce.rabbitquest.network.ShopNetwork;
import com.tr4nce.rabbitquest.shop.TradeInventory;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

final class ShopScreen extends Screen {
    private static final String KEY_PREFIX = "rabbitquest.shop.";
    private static final String RESULT_OK = KEY_PREFIX + "result.ok";
    private static final int ROW_HEIGHT = 23;
    private static final int SCROLL_GUTTER = 20;
    private static final int LEFT_COLOR = 0xFFDAD6CA;
    private static final int TEXT_COLOR = 0xFF3F3F3F;
    private static final int DIVIDER_COLOR = 0xFF8B8478;

    enum View { SHOP, PRODUCTS, CATEGORIES }

    record Position(View view, String selection, int listScroll, int detailScroll,
                    Set<String> expanded, String search, String previousSelection,
                    int previousListScroll, int previousDetailScroll) {}

    private record CatalogRow(CompoundTag entry, boolean category, boolean expanded, int count) {}

    private final List<Caption> captions = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private final List<Consumer<Integer>> detailRows = new ArrayList<>();
    private CompoundTag snapshot;
    private View view = View.SHOP;
    private final Set<String> expandedCategories = new LinkedHashSet<>();
    private boolean categoriesInitialized;
    private String search = "";
    private EditBox searchBox;
    private String previousSelection = "";
    private int previousListScroll;
    private int previousDetailScroll;
    private String selection = "";
    private String message = "";
    private String pendingRequest = "";
    private String pendingAction = "";
    private String quantity = "1";
    private int rewardOption;
    private int leftScroll;
    private int detailScroll;
    private int leftCount;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int detailX;
    private int detailWidth;
    private int listWidth;
    private int listTop;
    private int detailTop;
    private int detailsBottom;
    private ShopDraft draft;
    private Runnable confirmLeave;
    private Runnable leaveAfterSave;
    private Consumer<Integer> sampleTarget;
    private int scrollBeforeSample;
    private int editOption;
    private int inventoryCheckTicks;
    private List<ItemStack> lastInventory = List.of();

    ShopScreen(CompoundTag snapshot) {
        super(label("title"));
        this.snapshot = snapshot;
        Position position = QuestBookClient.shopPosition;
        if (position != null && (position.view() == View.SHOP || snapshot.getBoolean("admin"))) {
            view = position.view();
            expandedCategories.addAll(position.expanded());
            categoriesInitialized = true;
            search = position.search();
            previousSelection = position.previousSelection();
            previousListScroll = position.previousListScroll();
            previousDetailScroll = position.previousDetailScroll();
            selection = position.selection();
            leftScroll = position.listScroll();
            detailScroll = position.detailScroll();
        }
    }

    @Override
    public void removed() {
        int browsingScroll = draft == null ? detailScroll : 0;
        QuestBookClient.shopPosition = new Position(view, selection, leftScroll, browsingScroll,
                Set.copyOf(expandedCategories), search, previousSelection, previousListScroll, previousDetailScroll);
        super.removed();
    }

    void receive(CompoundTag incoming) {
        snapshot = incoming;
        String result = incoming.getString("message");
        String reply = incoming.getString("requestId");
        if (!result.isEmpty() && !pendingRequest.isEmpty() && reply.equals(pendingRequest)) {
            Runnable afterSave = leaveAfterSave;
            leaveAfterSave = null;
            String action = pendingAction;
            pendingRequest = "";
            pendingAction = "";
            message = result;
            if (result.equals(RESULT_OK)) {
                if ("buy".equals(action)) {
                    QuestBookClient.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6F, 1.1F);
                }
                if (draft != null) {
                    selection = draft.id;
                    draft = null;
                    sampleTarget = null;
                    detailScroll = 0;
                }
                if (afterSave != null) {
                    afterSave.run();
                    if (minecraft.screen != this) {
                        return;
                    }
                }
            } else {
                QuestBookClient.playSound(SoundEvents.VILLAGER_NO, 0.4F, 1.0F);
                if (result.equals(KEY_PREFIX + "result.stale") && draft != null) {
                    if (canRetryDraft()) {
                        draft.baseRevision = snapshot.getLong("revision");
                    } else {
                        draft.conflicted = true;
                        message = KEY_PREFIX + "result.edit_conflict";
                    }
                }
            }
        }
        if (!snapshot.getBoolean("admin") && view != View.SHOP) {
            pendingRequest = "";
            pendingAction = "";
            confirmLeave = null;
            discardDraft();
            switchView(View.SHOP);
            return;
        }
        rebuild();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - BookmarkTabs.GUTTER - 12, 510);
        panelHeight = Math.min(height - 12, 328);
        left = BookmarkTabs.GUTTER + (width - BookmarkTabs.GUTTER - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listWidth = Math.max(66, Math.min(146, panelWidth / 3));
        detailX = left + listWidth + 12;
        detailWidth = left + panelWidth - SCROLL_GUTTER - detailX;
        listTop = top + 53;
        detailTop = top + 55;
        detailsBottom = top + panelHeight - 53;
        captions.clear();
        icons.clear();
        detailRows.clear();
        searchBox = null;

        if (!snapshot.getBoolean("admin")) {
            view = View.SHOP;
        }
        if (confirmLeave != null) {
            buildLeavePrompt();
            return;
        }

        addButton(label("title"), left + 7, top + 6, listWidth - 7, () -> leaveEditor(() -> switchView(View.SHOP)))
                .active = view != View.SHOP;
        if (snapshot.getBoolean("admin")) {
            int half = (detailWidth - 4) / 2;
            addButton(label("products"), detailX, top + 6, half, () -> leaveEditor(() -> switchView(View.PRODUCTS)))
                    .active = view != View.PRODUCTS;
            addButton(label("categories"), detailX + half + 4, top + 6, detailWidth - half - 4,
                    () -> leaveEditor(() -> switchView(View.CATEGORIES))).active = view != View.CATEGORIES;
        }

        buildList();
        if (sampleTarget != null) {
            buildSamplePicker();
        } else if (draft != null) {
            buildEditor();
        } else {
            buildDetails();
        }
        int visibleRows = detailCapacity();
        detailScroll = Mth.clamp(detailScroll, 0, Math.max(0, detailRows.size() - visibleRows));
        for (int i = detailScroll; i < Math.min(detailScroll + visibleRows, detailRows.size()); i++) {
            detailRows.get(i).accept(detailTop + (i - detailScroll) * ROW_HEIGHT);
        }
        if (detailRows.size() > visibleRows) {
            addButton(label("up"), detailX + detailWidth + 2, detailTop, 14, () -> scrollDetails(-1))
                    .active = detailScroll > 0 && pendingRequest.isEmpty();
            addButton(label("down"), detailX + detailWidth + 2, detailTop + ROW_HEIGHT, 14,
                    () -> scrollDetails(1)).active = detailScroll + visibleRows < detailRows.size() && pendingRequest.isEmpty();
        }
        BookmarkTabs.attach(QuestBookClient.Tab.SHOP, left, top, pendingRequest.isEmpty(),
                this::addRenderableWidget);
        addButton(label("close"), left + panelWidth - 62, top + panelHeight - 25, 55, this::onClose);
        if (!message.isEmpty()) {
            caption(Component.translatable(message), left + 7, top + panelHeight - 19, panelWidth - 78);
        }
    }

    private void buildList() {
        List<CatalogRow> rows;
        if (view == View.SHOP) {
            int clearWidth = 20;
            searchBox = input("search", search, left + 7, top + 29, listWidth - 11 - clearWidth,
                    96, this::updateSearch);
            searchBox.setHint(label("search"));
            Button clear = addButton(Component.literal("×"), left + listWidth - clearWidth, top + 29,
                    clearWidth, () -> {
                        updateSearch("");
                        focusSearch(0);
                    });
            clear.setTooltip(Tooltip.create(label("clear_search")));
            clear.active = !search.isEmpty() && pendingRequest.isEmpty();
            rows = catalogRows();
        } else {
            String key = view == View.CATEGORIES ? "categories" : "products";
            rows = sortedEntries(key).stream().map(entry -> new CatalogRow(entry, false, false, 0)).toList();
            addButton(label("new"), left + 7, top + 29, listWidth - 7, () -> leaveEditor(this::newDraft))
                    .active = pendingRequest.isEmpty();
        }

        leftCount = rows.size();
        leftScroll = Mth.clamp(leftScroll, 0, Math.max(0, leftCount - leftCapacity()));
        boolean selectedInList = rows.stream()
                .anyMatch(row -> !row.category() && row.entry().getString("id").equals(selection));
        boolean retainCollapsedSelection = view == View.SHOP && search.isBlank() && isListedProduct(selection);
        if (!selectedInList && !retainCollapsedSelection) {
            String replacement = view == View.SHOP ? "" : rows.isEmpty() ? "" : rows.getFirst().entry().getString("id");
            if (!selection.equals(replacement) && draft == null) {
                resetDetails();
            }
            selection = replacement;
        }

        int lastRow = Math.min(leftCount, leftScroll + leftCapacity());
        for (int i = leftScroll; i < lastRow; i++) {
            renderCatalogRow(rows.get(i), listTop + (i - leftScroll) * ROW_HEIGHT);
        }
        if (leftCount == 0) {
            caption(label(search.isBlank() || view != View.SHOP ? "empty" : "no_matches"),
                    left + 7, listTop + 6, listWidth - 7);
        }
        if (leftCount > leftCapacity()) {
            int footerY = detailsBottom + 3;
            addButton(label("up"), left + 7, footerY, 14, () -> scrollList(-1))
                    .active = leftScroll > 0 && pendingRequest.isEmpty();
            addButton(label("down"), left + listWidth - 14, footerY, 14, () -> scrollList(1))
                    .active = lastRow < leftCount && pendingRequest.isEmpty();
            caption(label("rows", leftScroll + 1, lastRow, leftCount), left + 24, footerY + 6, listWidth - 41);
        }
    }

    private List<CompoundTag> sortedEntries(String key) {
        List<CompoundTag> entries = new ArrayList<>();
        for (Tag entry : list(snapshot, key)) {
            entries.add((CompoundTag) entry);
        }
        entries.sort(Comparator.comparingInt((CompoundTag entry) -> entry.getInt("order"))
                .thenComparing(entry -> entry.getString("id")));
        return entries;
    }

    private boolean isListedProduct(String id) {
        CompoundTag product = find(list(snapshot, "products"), id);
        return product.getBoolean("enabled")
                && find(list(snapshot, "categories"), product.getString("category")).getBoolean("visible");
    }

    private List<CatalogRow> catalogRows() {
        boolean searching = !search.isBlank();
        String[] terms = search.strip().toLowerCase(Locale.ROOT).split("\\s+");
        var productsByCategory = new LinkedHashMap<String, List<CompoundTag>>();
        for (CompoundTag product : sortedEntries("products")) {
            if (!product.getBoolean("enabled")) {
                continue;
            }
            String title = product.getString("name");
            String text = (title + " " + product.getString("description") + " "
                    + product.getString("id")).toLowerCase(Locale.ROOT);
            if (!searching || Arrays.stream(terms)
                    .allMatch(term -> text.contains(term) || JeCharactersSearch.matchesTitle(title, term))) {
                productsByCategory.computeIfAbsent(product.getString("category"), key -> new ArrayList<>()).add(product);
            }
        }
        List<CatalogRow> rows = new ArrayList<>();
        for (CompoundTag category : sortedEntries("categories")) {
            String id = category.getString("id");
            List<CompoundTag> products = productsByCategory.get(id);
            if (!category.getBoolean("visible") || products == null) {
                continue;
            }
            if (!categoriesInitialized && !searching) {
                categoriesInitialized = true;
                expandedCategories.add(id);
                if (selection.isEmpty()) {
                    selection = products.getFirst().getString("id");
                }
            }
            boolean expanded = searching || expandedCategories.contains(id);
            rows.add(new CatalogRow(category, true, expanded, products.size()));
            if (expanded) {
                for (CompoundTag product : products) {
                    rows.add(new CatalogRow(product, false, false, 0));
                }
            }
        }
        return rows;
    }

    private void renderCatalogRow(CatalogRow row, int y) {
        CompoundTag entry = row.entry();
        String id = entry.getString("id");
        String name = entry.getString("name");
        if (row.category()) {
            Component heading = label(row.expanded() ? "category_expanded" : "category_collapsed", name, row.count());
            addButton(heading, left + 7, y, listWidth - 7, () -> {
                if (!expandedCategories.remove(id)) {
                    expandedCategories.add(id);
                }
                rebuild();
            }).active = search.isBlank() && pendingRequest.isEmpty();
            return;
        }

        boolean playerShop = view == View.SHOP;
        boolean hasIcon = !decode(entry.getCompound("icon")).isEmpty();
        int offset = (playerShop ? 7 : 0) + (hasIcon ? 26 : 7);
        int width = listWidth - offset;
        Component title = Component.literal(name);
        Component tooltip = title;
        if (playerShop) {
            Component status = label("status." + purchaseStatus(entry));
            tooltip = Component.literal(name + " · ").append(status);
            String suffix = " · " + status.getString();
            int nameWidth = width - 8 - font.width(suffix);
            if (nameWidth > 8) {
                title = Component.literal(font.plainSubstrByWidth(name, nameWidth) + suffix);
            }
        }
        Button button = addButton(title, left + offset, y, width, () -> leaveEditor(() -> select(id)));
        button.active = !selection.equals(id) && pendingRequest.isEmpty();
        if (playerShop) {
            button.setTooltip(Tooltip.create(tooltip));
        }
        if (hasIcon) {
            icon(entry.getCompound("icon"), left + offset - 19, y + 2, 0);
        }
    }

    private String purchaseStatus(CompoundTag entry) {
        if (!entry.getBoolean("available")) {
            String window = entry.getString("windowStatus");
            return window.isEmpty() ? "unavailable" : window;
        }
        if (entry.getInt("stock") == 0) {
            return "sold_out";
        }
        if (entry.getInt("weeklyLimit") > 0 && entry.getInt("weeklyPurchased") >= entry.getInt("weeklyLimit")) {
            return "weekly_limit";
        }
        if (entry.getInt("totalLimit") > 0 && entry.getInt("purchased") >= entry.getInt("totalLimit")) {
            return "total_limit";
        }
        if (minecraft.player != null) {
            List<TradeInventory.Cost> costs = new ArrayList<>();
            for (Tag tag : list(entry, "costs")) {
                CompoundTag cost = (CompoundTag) tag;
                costs.add(new TradeInventory.Cost(decode(cost.getCompound("item")), cost.getInt("count"),
                        cost.getBoolean("exact")));
            }
            if (TradeInventory.plan(minecraft.player.getInventory().items, costs, List.of(), 1).failure()
                    == TradeInventory.Failure.MISSING_COST) {
                return "missing_cost";
            }
        }
        return "available";
    }

    private void updateSearch(String value) {
        int cursor = searchBox.getCursorPosition();
        boolean wasSearching = !search.isBlank();
        search = value;
        if (!wasSearching && !search.isBlank()) {
            previousSelection = selection;
            previousListScroll = leftScroll;
            previousDetailScroll = detailScroll;
            selection = "";
            resetDetails();
        }
        if (search.isBlank() && wasSearching) {
            selection = previousSelection;
            resetDetails();
            leftScroll = previousListScroll;
            detailScroll = previousDetailScroll;
        } else if (!search.isBlank()) {
            leftScroll = 0;
        }
        rebuild();
        focusSearch(cursor);
    }

    private void focusSearch(int cursor) {
        if (searchBox != null) {
            setFocused(searchBox);
            searchBox.setCursorPosition(Mth.clamp(cursor, 0, search.length()));
        }
    }

    private void buildDetails() {
        CompoundTag entry = find(list(snapshot, view == View.CATEGORIES ? "categories" : "products"), selection);
        if (entry.isEmpty()) {
            caption(label("select_entry"), detailX, detailTop, detailWidth);
            return;
        }

        caption(Component.literal(entry.getString("name")), detailX, top + 35, detailWidth);
        if (view == View.CATEGORIES) {
            textRow(Component.literal(entry.getString("id")));
            textRow(label(entry.getBoolean("visible") ? "visible" : "hidden"));
            adminActions(entry);
            return;
        }

        if (!entry.getString("description").isBlank()) {
            for (var line : font.split(Component.literal(entry.getString("description")), detailWidth)) {
                detailRows.add(y -> captions.add(new Caption(line, detailX, y + 6)));
            }
        }
        textRow(label("costs"));
        for (Tag element : list(entry, "costs")) {
            CompoundTag cost = (CompoundTag) element;
            itemDetails(cost.getCompound("item"), cost.getInt("count"),
                    label(cost.getBoolean("exact") ? "exact" : "item_id"));
        }

        textRow(label("rewards"));
        ListTag options = list(entry, "options");
        rewardOption = Mth.clamp(rewardOption, 0, Math.max(0, options.size() - 1));
        if (!options.isEmpty()) {
            if (entry.getBoolean("choice")) {
                for (int i = 0; i < options.size(); i++) {
                    int optionIndex = i;
                    boolean selected = optionIndex == rewardOption;
                    detailRows.add(y -> addButton(
                            label(selected ? "option_selected" : "option_select", optionIndex + 1),
                            detailX, y, detailWidth, () -> {
                                rewardOption = optionIndex;
                                rebuild();
                            }).active = !selected && pendingRequest.isEmpty());
                    for (Tag item : list(options.getCompound(optionIndex), "items")) {
                        CompoundTag stack = (CompoundTag) item;
                        int count = stack.contains("rewardCount") ? stack.getInt("rewardCount") : stack.getInt("count");
                        itemDetails(stack, count, Component.empty());
                    }
                }
            } else {
                for (Tag item : list(options.getCompound(0), "items")) {
                    CompoundTag stack = (CompoundTag) item;
                    int count = stack.contains("rewardCount") ? stack.getInt("rewardCount") : stack.getInt("count");
                    itemDetails(stack, count, Component.empty());
                }
            }
        }
        if (entry.getInt("stock") >= 0) {
            textRow(label("stock_value", entry.getInt("stock")));
        }
        if (entry.getInt("totalLimit") > 0) {
            textRow(label("total_progress", entry.getInt("purchased"), entry.getInt("totalLimit")));
        }
        if (entry.getInt("weeklyLimit") > 0) {
            textRow(label("weekly_progress", entry.getInt("weeklyPurchased"), entry.getInt("weeklyLimit")));
        }

        if (view == View.PRODUCTS) {
            adminActions(entry);
            return;
        }

        int footerY = detailsBottom + 3;
        int inputWidth = entry.getBoolean("batch") && !entry.getBoolean("choice") ? 48 : 0;
        if (inputWidth > 0) {
            EditBox amount = input("amount", quantity, detailX, footerY, inputWidth, 9, value -> quantity = value);
            amount.setFilter(value -> value.chars().allMatch(Character::isDigit));
        }
        String status = purchaseStatus(entry);
        if (!status.equals("available")) {
            caption(label("status." + status), detailX, top + 45, detailWidth);
        }
        addButton(label("buy"), detailX + (inputWidth == 0 ? 0 : inputWidth + 4), footerY,
                detailWidth - (inputWidth == 0 ? 0 : inputWidth + 4), () -> buy(entry))
                .active = status.equals("available") && !options.isEmpty() && pendingRequest.isEmpty();
    }

    private void adminActions(CompoundTag entry) {
        int footerY = detailsBottom + 3;
        int half = (detailWidth - 4) / 2;
        addButton(label("edit"), detailX, footerY, half, () -> beginEdit(entry, false))
                .active = pendingRequest.isEmpty();
        addButton(label("copy"), detailX + half + 4, footerY, detailWidth - half - 4, () -> beginEdit(entry, true))
                .active = pendingRequest.isEmpty();
    }

    private void buildEditor() {
        if (draft.category) {
            caption(label("edit_category"), detailX, top + 35, detailWidth);
        } else {
            int col = detailWidth / Section.values().length;
            for (Section section : Section.values()) {
                int sectionX = detailX + col * section.ordinal();
                int sectionWidth = section == Section.LIMITS ? detailWidth - col * section.ordinal() : col;
                addButton(label(section.name().toLowerCase(Locale.ROOT)), sectionX, top + 30,
                        sectionWidth - 2, () -> chooseSection(section)).active = draft.section != section;
            }
        }

        switch (draft.section) {
            case GENERAL -> buildGeneralFields();
            case COSTS -> buildCosts();
            case REWARDS -> buildRewards();
            case LIMITS -> buildLimits();
        }

        int footerY = detailsBottom + 3;
        int half = (detailWidth - 4) / 2;
        addButton(label("save"), detailX, footerY, half, this::save)
                .active = pendingRequest.isEmpty() && !draft.conflicted;
        addButton(label("cancel"), detailX + half + 4, footerY, detailWidth - half - 4, this::cancelEdit)
                .active = pendingRequest.isEmpty();
    }

    private void buildGeneralFields() {
        if (!draft.existing) {
            textField("id", draft.id, 128, value -> draft.id = value);
        } else {
            textRow(Component.literal(draft.id));
        }
        textField("name", draft.data.getString("name"), 96, value -> draft.data.putString("name", value));
        textField("order", draft.order, 11, value -> draft.order = value);
        detailRows.add(y -> {
            icon(draft.data.getCompound("icon"), detailX + 1, y + 2, 0);
            addButton(label("sample_icon"), detailX + 22, y, detailWidth - 22, this::pickIcon);
        });

        if (draft.category) {
            toggle("visible", draft.data.getBoolean("visible"), value -> draft.data.putBoolean("visible", value));
        } else {
            textField("description", draft.data.getString("description"), 512,
                    value -> draft.data.putString("description", value));
            detailRows.add(y -> addButton(label("category_value", categoryName(draft.data.getString("category"))),
                    detailX, y, detailWidth, this::chooseProductCategory));
            toggle("enabled", draft.data.getBoolean("enabled"), value -> draft.data.putBoolean("enabled", value));
        }
    }

    private void buildCosts() {
        ListTag costs = list(draft.data, "costs");
        detailRows.add(y -> addButton(label("add_cost"), detailX, y, detailWidth,
                () -> pickSample(slot -> {
                    CompoundTag row = new CompoundTag();
                    row.put("item", sample(slot, 1));
                    row.putInt("count", 1);
                    costs.add(row);
                    detailScroll = Math.max(0, costs.size() * 2 + 1 - detailCapacity());
                })));
        for (int index = 0; index < costs.size(); index++) {
            int rowIndex = index;
            CompoundTag cost = costs.getCompound(index);
            detailRows.add(y -> {
                icon(cost.getCompound("item"), detailX + 2, y + 2, 0);
                int inputWidth = Math.max(28, detailWidth / 5);
                input("amount", draft.count(cost), detailX + 22, y, inputWidth, 9,
                        value -> draft.setCount(cost, value));
                addButton(label(cost.getBoolean("exact") ? "exact" : "item_id"),
                        detailX + inputWidth + 26, y, detailWidth - inputWidth - 26, () -> {
                            cost.putBoolean("exact", !cost.getBoolean("exact"));
                            rebuild();
                        });
            });
            detailRows.add(y -> rowActions(costs, rowIndex, y,
                    () -> pickSample(slot -> cost.put("item", sample(slot, 1)))));
        }
    }

    private void buildRewards() {
        ListTag options = list(draft.data, "options");
        toggle("choice", draft.data.getBoolean("choice"), value -> {
            draft.data.putBoolean("choice", value);
            if (value) {
                draft.data.putBoolean("batch", false);
            } else {
                editOption = 0;
            }
        });
        if (options.isEmpty()) {
            options.add(newOption());
        }
        editOption = Mth.clamp(editOption, 0, options.size() - 1);
        CompoundTag option = options.getCompound(editOption);
        ListTag items = list(option, "items");
        if (draft.data.getBoolean("choice")) {
            detailRows.add(y -> addButton(label("option", editOption + 1, options.size()), detailX, y, detailWidth,
                    () -> {
                        editOption = (editOption + 1) % options.size();
                        rebuild();
                    }));
            detailRows.add(y -> {
                int half = (detailWidth - 4) / 2;
                addButton(label("add"), detailX, y, half, () -> {
                    options.add(newOption());
                    editOption = options.size() - 1;
                    rebuild();
                });
                addButton(label("remove"), detailX + half + 4, y, detailWidth - half - 4, () -> {
                    options.remove(editOption);
                    rebuild();
                }).active = options.size() > 1;
            });
            detailRows.add(y -> {
                int half = (detailWidth - 4) / 2;
                addButton(label("up"), detailX, y, half, () -> moveOption(options, -1)).active = editOption > 0;
                addButton(label("down"), detailX + half + 4, y, detailWidth - half - 4,
                        () -> moveOption(options, 1)).active = editOption + 1 < options.size();
            });
        }
        detailRows.add(y -> addButton(label("add_reward"), detailX, y, detailWidth, () -> pickSample(slot -> {
            items.add(sample(slot, 1));
            int controls = draft.data.getBoolean("choice") ? 5 : 2;
            detailScroll = Math.max(0, items.size() * 2 + controls - detailCapacity());
        })));
        for (int index = 0; index < items.size(); index++) {
            int rowIndex = index;
            CompoundTag item = items.getCompound(index);
            detailRows.add(y -> {
                icon(item, detailX + 2, y + 2, 0);
                input("amount", draft.count(item), detailX + 22, y, Math.min(58, detailWidth - 22), 9,
                        value -> draft.setCount(item, value));
                ItemStack stack = decode(item);
                if (!stack.isEmpty()) {
                    caption(stack.getHoverName(), detailX + 84, y + 6, detailWidth - 84);
                }
            });
            detailRows.add(y -> rowActions(items, rowIndex, y, () -> pickSample(slot -> {
                CompoundTag replacement = sample(slot, 1);
                draft.setCount(replacement, draft.count(item));
                items.set(rowIndex, replacement);
            })));
        }
    }

    private void buildLimits() {
        textField("start", draft.start, 32, value -> draft.start = value);
        textField("end", draft.end, 32, value -> draft.end = value);
        textField("total_limit", draft.totalLimit, 10, value -> draft.totalLimit = value);
        textField("weekly_limit", draft.weeklyLimit, 10, value -> draft.weeklyLimit = value);
        textField("stock", draft.stock, 11, value -> {
            draft.stock = value;
            draft.stockEdited = true;
        });
        if (!draft.data.getBoolean("choice")) {
            toggle("batch", draft.data.getBoolean("batch"), value -> draft.data.putBoolean("batch", value));
        }
    }

    private void moveOption(ListTag options, int direction) {
        int previousIndex = editOption;
        editOption += direction;
        move(options, previousIndex, direction);
    }

    private void rowActions(ListTag rows, int index, int y, Runnable replace) {
        int third = detailWidth / 6;
        int remaining = detailWidth - third * 2;
        addButton(label("replace"), detailX, y, remaining / 2 - 2, replace);
        addButton(label("remove"), detailX + remaining / 2, y, remaining - remaining / 2 - 2, () -> {
            rows.remove(index);
            rebuild();
        });
        addButton(label("up"), detailX + remaining, y, third - 2, () -> move(rows, index, -1)).active = index > 0;
        addButton(label("down"), detailX + remaining + third, y, third, () -> move(rows, index, 1))
                .active = index + 1 < rows.size();
    }

    private void move(ListTag rows, int index, int direction) {
        Tag entry = rows.remove(index);
        rows.add(index + direction, entry);
        rebuild();
    }

    private void buildSamplePicker() {
        caption(label("pick"), detailX, top + 35, detailWidth);
        int columns = Math.min(9, Math.max(1, detailWidth / 23));
        for (int first = 0; first < 36; first += columns) {
            int rowStart = first;
            detailRows.add(y -> {
                for (int column = 0; column < columns && rowStart + column < 36; column++) {
                    int slot = rowStart + column;
                    int x = detailX + column * 23;
                    ItemStack stack = minecraft.player.getInventory().getItem(slot).copy();
                    Button button = addButton(Component.empty(), x, y, 21, () -> chooseSample(slot, stack));
                    button.active = !stack.isEmpty();
                    if (!stack.isEmpty()) {
                        icons.add(new Icon(stack, x + 2, y + 2));
                    }
                }
            });
        }
        addButton(label("cancel"), detailX, detailsBottom + 3, detailWidth, this::cancelSample);
    }

    private void pickIcon() {
        pickSample(slot -> draft.data.put("icon", sample(slot, 1)));
    }

    private void pickSample(Consumer<Integer> target) {
        scrollBeforeSample = detailScroll;
        detailScroll = 0;
        sampleTarget = target;
        rebuild();
    }

    private void chooseSample(int slot, ItemStack shown) {
        ItemStack current = minecraft.player.getInventory().getItem(slot);
        if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, shown)) {
            message = KEY_PREFIX + "result.sample_changed";
            rebuild();
            return;
        }
        message = "";
        Consumer<Integer> target = sampleTarget;
        sampleTarget = null;
        detailScroll = scrollBeforeSample;
        target.accept(slot);
        rebuild();
    }

    private void cancelSample() {
        sampleTarget = null;
        detailScroll = scrollBeforeSample;
        rebuild();
    }

    private CompoundTag sample(int slot, int count) {
        CompoundTag result = new CompoundTag();
        result.putInt("slot", slot);
        result.putInt("count", count);
        ItemStack item = minecraft.player.getInventory().getItem(slot);
        if (!item.isEmpty() && item.copyWithCount(1).save(minecraft.player.registryAccess()) instanceof CompoundTag stack) {
            result.put("expected", stack);
        }
        return result;
    }

    private void switchView(View newView) {
        if (!pendingRequest.isEmpty()) {
            return;
        }
        view = newView;
        selection = "";
        search = "";
        leftScroll = 0;
        select("");
    }

    private void select(String id) {
        selection = id;
        resetDetails();
        draft = null;
        sampleTarget = null;
        rebuild();
    }

    private void resetDetails() {
        quantity = "1";
        rewardOption = 0;
        detailScroll = 0;
        message = "";
    }

    private void chooseProductCategory() {
        List<CompoundTag> categories = new ArrayList<>();
        for (Tag entry : list(snapshot, "categories")) {
            categories.add((CompoundTag) entry);
        }
        categories.sort(Comparator.comparingInt((CompoundTag category) -> category.getInt("order"))
                .thenComparing(category -> category.getString("id")));
        String next = nextId(categories, draft.data.getString("category"));
        if (next.isEmpty() && !categories.isEmpty()) {
            next = categories.getFirst().getString("id");
        }
        draft.data.putString("category", next);
        rebuild();
    }

    private static String nextId(List<CompoundTag> entries, String current) {
        if (current.isEmpty()) {
            return entries.isEmpty() ? "" : entries.getFirst().getString("id");
        }
        for (int i = 0; i < entries.size() - 1; i++) {
            if (entries.get(i).getString("id").equals(current)) {
                return entries.get(i + 1).getString("id");
            }
        }
        return "";
    }

    private String categoryName(String id) {
        CompoundTag category = find(list(snapshot, "categories"), id);
        return category.isEmpty() ? label("none").getString() : category.getString("name");
    }

    private void newDraft() {
        boolean isCategory = view == View.CATEGORIES;
        CompoundTag data = new CompoundTag();
        data.putBoolean(isCategory ? "visible" : "enabled", true);
        data.putInt("stock", -1);
        if (!isCategory) {
            ListTag categories = list(snapshot, "categories");
            if (!categories.isEmpty()) {
                data.putString("category", categories.getCompound(0).getString("id"));
            }
            data.put("costs", new ListTag());
            data.put("options", new ListTag());
        }
        beginEdit(data, false);
    }

    private void beginEdit(CompoundTag entry, boolean copy) {
        boolean hasId = !entry.getString("id").isEmpty();
        boolean existing = hasId && !copy;
        String id = copy ? entry.getString("id") + "_copy" : entry.getString("id");
        if (copy) {
            String base = id;
            int suffix = 2;
            String key = view == View.CATEGORIES ? "categories" : "products";
            while (!find(list(snapshot, key), id).isEmpty()) {
                id = base + suffix++;
            }
        }
        draft = new ShopDraft(view == View.CATEGORIES, existing, id, entry, snapshot.getLong("revision"));
        editOption = 0;
        detailScroll = 0;
        message = "";
        rebuild();
    }

    private void chooseSection(Section section) {
        draft.section = section;
        detailScroll = 0;
        rebuild();
    }

    @Override
    public void onClose() {
        if (confirmLeave != null) {
            confirmLeave = null;
            rebuild();
        } else {
            leaveEditor(super::onClose);
        }
    }

    boolean hasProtectedDraft() {
        return draft != null && (!pendingRequest.isEmpty() || draft.isDirty());
    }

    void leaveEditor(Runnable action) {
        if (!pendingRequest.isEmpty() && draft != null) {
            return;
        }
        if (draft != null && draft.isDirty()) {
            confirmLeave = action;
            rebuild();
        } else {
            discardDraft();
            action.run();
            if (minecraft.screen == this) {
                rebuild();
            }
        }
    }

    private void buildLeavePrompt() {
        int x = left + 12;
        int y = top + 20;
        int buttonWidth = panelWidth - 24;
        caption(label("unsaved"), x, y, buttonWidth);
        addButton(label("save"), x, y + ROW_HEIGHT, buttonWidth, () -> {
            Runnable action = confirmLeave;
            confirmLeave = null;
            save();
            if (!pendingRequest.isEmpty()) {
                leaveAfterSave = action;
            }
        }).active = !draft.conflicted;
        addButton(label("discard"), x, y + 2 * ROW_HEIGHT, buttonWidth, () -> {
            Runnable action = confirmLeave;
            confirmLeave = null;
            discardDraft();
            action.run();
            if (minecraft.screen == this) {
                rebuild();
            }
        });
        addButton(label("continue_editing"), x, y + 3 * ROW_HEIGHT, buttonWidth, this::onClose);
    }

    private void discardDraft() {
        if (draft != null) {
            draft = null;
            sampleTarget = null;
            detailScroll = 0;
        }
    }

    private void cancelEdit() {
        leaveEditor(this::finishCancel);
    }

    private void finishCancel() {
        message = "";
        rebuild();
    }

    private void save() {
        String key = draft.category ? "categories" : "products";
        String id = draft.normalizedId();
        CompoundTag current = find(list(snapshot, key), id);
        if (!draft.existing && !current.isEmpty()) {
            message = KEY_PREFIX + "result.duplicate_id";
            rebuild();
            return;
        }

        CompoundTag data;
        try {
            data = draft.serialize(id, current);
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            message = KEY_PREFIX + "result.fields";
            rebuild();
            return;
        }
        draft.id = id;
        CompoundTag request = request(draft.category ? "save_category" : "save_product");
        request.putLong("revision", draft.baseRevision);
        request.putBoolean("create", !draft.existing);
        request.put(draft.category ? "category" : "product", data);
        submit(request);
    }

    private boolean canRetryDraft() {
        String key = draft.category ? "categories" : "products";
        return draft.canRetry(find(list(snapshot, key), draft.id));
    }

    private void buy(CompoundTag entry) {
        int amount;
        try {
            amount = entry.getBoolean("batch") && !entry.getBoolean("choice") ? ShopDraft.positive(quantity) : 1;
        } catch (IllegalArgumentException invalid) {
            message = KEY_PREFIX + "result.invalid_quantity";
            rebuild();
            return;
        }
        CompoundTag request = request("buy");
        request.putString("id", entry.getString("id"));
        request.putLong("version", entry.getLong("version"));
        request.putInt("amount", amount);
        request.putInt("option", entry.getBoolean("choice") ? rewardOption : 0);
        submit(request);
    }

    private CompoundTag request(String action) {
        CompoundTag request = new CompoundTag();
        request.putString("action", action);
        request.putLong("revision", snapshot.getLong("revision"));
        request.putString("requestId", UUID.randomUUID().toString());
        return request;
    }

    private void submit(CompoundTag request) {
        if (!pendingRequest.isEmpty()) {
            return;
        }
        pendingRequest = request.getString("requestId");
        pendingAction = request.getString("action");
        message = KEY_PREFIX + "pending";
        ShopNetwork.send(request);
        rebuild();
    }

    private int leftCapacity() {
        return Math.max(1, (detailsBottom - listTop) / ROW_HEIGHT);
    }

    private int detailCapacity() {
        return Math.max(1, (detailsBottom - detailTop) / ROW_HEIGHT);
    }

    @Override
    public void tick() {
        if (view != View.SHOP || minecraft.player == null || ++inventoryCheckTicks < 10) {
            return;
        }
        inventoryCheckTicks = 0;
        List<ItemStack> inventory = minecraft.player.getInventory().items;
        if (!ItemStack.listMatches(lastInventory, inventory)) {
            lastInventory = inventory.stream().map(ItemStack::copy).toList();
            rebuild();
        }
    }

    private void rebuild() {
        if (minecraft != null) {
            int cursor = searchBox != null && getFocused() == searchBox ? searchBox.getCursorPosition() : -1;
            rebuildWidgets();
            if (cursor >= 0) {
                focusSearch(cursor);
            }
        }
    }

    private void scrollList(int step) {
        int scroll = Mth.clamp(leftScroll + step, 0, Math.max(0, leftCount - leftCapacity()));
        if (scroll != leftScroll) {
            leftScroll = scroll;
            rebuild();
        }
    }

    private void scrollDetails(int step) {
        int scroll = Mth.clamp(detailScroll + step, 0, Math.max(0, detailRows.size() - detailCapacity()));
        if (scroll != detailScroll) {
            detailScroll = scroll;
            rebuild();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (confirmLeave != null) {
            return false;
        }
        if (mouseY >= listTop && mouseY < detailsBottom && vertical != 0) {
            int step = vertical > 0 ? -1 : 1;
            if (mouseX >= left && mouseX < detailX) {
                scrollList(step);
                return true;
            }
            if (mouseX >= detailX && mouseX < left + panelWidth) {
                scrollDetails(step);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        super.renderBackground(gui, mouseX, mouseY, delta);
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xFFC6C1B4);
        gui.renderOutline(left, top, panelWidth, panelHeight, 0xFF595449);
        gui.fill(left + 5, top + 28, left + listWidth + 4, detailsBottom + 15, LEFT_COLOR);
        gui.vLine(detailX - 4, top + 28, detailsBottom + 17, DIVIDER_COLOR);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        super.render(gui, mouseX, mouseY, delta);
        for (Caption caption : captions) {
            gui.drawString(font, caption.text(), caption.x(), caption.y(), TEXT_COLOR, false);
        }
        ItemStack tooltip = ItemStack.EMPTY;
        for (Icon icon : icons) {
            gui.renderItem(icon.stack(), icon.x(), icon.y());
            gui.renderItemDecorations(font, icon.stack(), icon.x(), icon.y());
            if (mouseX >= icon.x() && mouseX < icon.x() + 16 && mouseY >= icon.y() && mouseY < icon.y() + 16) {
                tooltip = icon.stack();
            }
        }
        if (!tooltip.isEmpty()) {
            gui.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void textRow(Component text) {
        detailRows.add(y -> caption(text, detailX, y + 6, detailWidth));
    }

    private void itemDetails(CompoundTag tag, int count, Component note) {
        detailRows.add(y -> {
            icon(tag, detailX + 2, y + 2, count);
            ItemStack stack = decode(tag);
            if (!stack.isEmpty()) {
                Component text = Component.literal(count + " × ").append(stack.getHoverName());
                if (!note.getString().isEmpty()) {
                    text = text.copy().append(" · ").append(note);
                }
                caption(text, detailX + 23, y + 6, detailWidth - 23);
            }
        });
    }

    private void textField(String key, String value, int maxLength, Consumer<String> change) {
        detailRows.add(y -> {
            int labelWidth = detailWidth / 3;
            caption(label(key), detailX, y + 6, labelWidth - 3);
            input(key, value, detailX + labelWidth, y, detailWidth - labelWidth, maxLength, change);
        });
    }

    private EditBox input(String key, String value, int x, int y, int size, int maxLength, Consumer<String> change) {
        EditBox edit = new EditBox(font, x, y + 1, size, 18, label(key));
        edit.setMaxLength(maxLength);
        edit.setValue(value);
        edit.setResponder(change);
        edit.setEditable(pendingRequest.isEmpty());
        return addRenderableWidget(edit);
    }

    private void toggle(String key, boolean state, Consumer<Boolean> change) {
        detailRows.add(y -> addButton(label("toggle", label(key), label(state ? "yes" : "no")), detailX, y,
                detailWidth, () -> {
                    change.accept(!state);
                    rebuild();
                }));
    }

    private Button addButton(Component label, int x, int y, int size, Runnable click) {
        Component fitted = font.width(label) + 8 > size
                ? Component.literal(font.plainSubstrByWidth(label.getString(), Math.max(0, size - 8)))
                : label;
        Button result = addRenderableWidget(Button.builder(fitted, button -> {
            if (pendingRequest.isEmpty() || label.equals(label("close"))) {
                click.run();
            }
        }).bounds(x, y, Math.max(1, size), 20).build());
        result.active = pendingRequest.isEmpty() || label.equals(label("close"));
        if (fitted != label) {
            result.setTooltip(Tooltip.create(label));
        }
        return result;
    }

    private void caption(Component text, int x, int y, int size) {
        if (size > 0) {
            var lines = font.split(text, size);
            if (!lines.isEmpty()) {
                captions.add(new Caption(lines.getFirst(), x, y));
            }
        }
    }

    private void icon(CompoundTag tag, int x, int y, int count) {
        ItemStack stack = decode(tag);
        if (!stack.isEmpty()) {
            icons.add(new Icon(count > 0 ? stack.copyWithCount(count) : stack, x, y));
        }
    }

    private ItemStack decode(CompoundTag tag) {
        if (tag.contains("slot")) {
            return ItemStack.parseOptional(minecraft.player.registryAccess(), tag.getCompound("expected"));
        }
        return ItemStack.parseOptional(minecraft.player.registryAccess(), tag);
    }

    private static CompoundTag find(ListTag entries, String id) {
        for (Tag tag : entries) {
            CompoundTag entry = (CompoundTag) tag;
            if (id.equals(entry.getString("id"))) {
                return entry;
            }
        }
        return new CompoundTag();
    }

    private static ListTag list(CompoundTag data, String key) {
        return data.getList(key, Tag.TAG_COMPOUND);
    }

    private static CompoundTag newOption() {
        CompoundTag option = new CompoundTag();
        option.put("items", new ListTag());
        return option;
    }

    private static Component label(String key, Object... args) {
        return Component.translatable(KEY_PREFIX + key, args);
    }

    private record Caption(FormattedCharSequence text, int x, int y) {}

    private record Icon(ItemStack stack, int x, int y) {}

}

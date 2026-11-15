package com.nereonix.nereonet_mvp.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ai.CountExtractionException;
import com.nereonix.nereonet_mvp.core.ai.FakeCountExtractor;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.speech.FakeSpeechClient;
import com.nereonix.nereonet_mvp.core.speech.SpeechToTextException;
import com.nereonix.nereonet_mvp.core.stock.StockLevel;
import com.nereonix.nereonet_mvp.core.stock.StockLevelRepository;
import com.nereonix.nereonet_mvp.core.stock.StockMovement;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P57, counting the store by voice, with both fakes (application-test.yml): the fake transcript "Five kilos of
 * tomatoes, eight kilos of chicken, thirty eggs, three cans of herring." gives Tomatoes 5 (High), Chicken breast 8 (Low,
 * by one word of its name), Eggs 30 (High) and "three cans of herring" not matched. Posting a recording only proposes;
 * Confirm applies the rows through StockService.count with the note "Voice count"; failures leave the stock alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VoiceCountFlowTest {

    private static final String VOICE = "/ship/stores/count/voice";

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired StockLevelRepository levelRepository;
    @Autowired StockService stockService;
    @Autowired FakeSpeechClient speech;
    @Autowired FakeCountExtractor extractor;

    Ship ship;
    User cook;
    User master;
    Item tomatoes;
    Item chicken;
    Item eggs;
    Item salt;
    Item herringOfAnotherShip;
    MockHttpSession session;

    @BeforeEach
    void setUp() {
        speech.reset();
        extractor.reset();
        ship = ship("9074729", "MV Demo");
        Ship otherShip = ship("9123456", "MV Other");
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        tomatoes = item(ship, "Tomatoes", "kg", ItemCategory.PROVISIONS);
        chicken = item(ship, "Chicken breast", "kg", ItemCategory.PROVISIONS);
        eggs = item(ship, "Eggs", "pcs", ItemCategory.PROVISIONS);
        salt = item(ship, "Salt", "kg", ItemCategory.PROVISIONS);
        item(ship, "Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);
        herringOfAnotherShip = item(otherShip, "Herring", "kg", ItemCategory.PROVISIONS);
        stock(tomatoes, "12");
        stock(chicken, "30");
        stock(salt, "15");
        // Eggs have no row: the store expects 0.
        session = new MockHttpSession();
    }

    @Test
    void postingARecordingShowsWhatWeHeardAndLeavesTheStockAlone() throws Exception {
        mockMvc.perform(record()).andExpect(redirectedUrl(VOICE));

        String html = review();
        assertThat(html).contains("<title>What we heard · Nereonet</title>",
                "Five kilos of tomatoes, eight kilos of chicken, thirty eggs, three cans of herring.");
        assertThat(row(html, "Tomatoes")).contains("name=\"rows[0].quantity\"", "value=\"5\"", "<q>Five kilos of tomatoes</q>",
                ">High<", "Store expects", "<span class=\"num\">12</span>").doesNotContain("is-low");
        assertThat(row(html, "Chicken breast")).contains("name=\"rows[1].quantity\"", "value=\"8\"",
                "<q>eight kilos of chicken</q>", ">Low<", "is-low", "<span class=\"num\">30</span>");
        assertThat(row(html, "Eggs")).contains("name=\"rows[2].quantity\"", "value=\"30\"", ">High<",
                "<span class=\"num\">0</span>");
        assertThat(html).contains("Not matched", "<q id=\"extra-0\">three cans of herring</q>",
                "name=\"extras[0].itemId\"", "<option value=\"\">Don&#39;t count</option>", ">Salt (kg)</option>",
                ">Confirm</button>", ">Discard</button>");
        // Only the vessel's active provisions are offered, never personal items or another vessel's.
        assertThat(html).doesNotContain("Shower gel", "Herring (kg)");

        // Nothing reached the store.
        assertThat(levels()).containsExactlyInAnyOrderEntriesOf(Map.of("Tomatoes", "12", "Chicken breast", "30",
                "Salt", "15"));
        assertThat(movements()).isEmpty();
        // The providers got the recording with the item names as key terms, and the text.
        assertThat(speech.calls()).hasSize(1);
        assertThat(speech.calls().get(0).contentType()).isEqualTo("audio/webm;codecs=opus");
        assertThat(speech.calls().get(0).keyterms()).containsExactly("Chicken breast", "Eggs", "Salt", "Tomatoes");
        assertThat(extractor.transcripts()).containsExactly(FakeSpeechClient.SENTENCE);
    }

    @Test
    void withJavaScriptTheAnswerIsTheReviewAddress() throws Exception {
        mockMvc.perform(record().accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewUrl").value(VOICE))
                .andExpect(jsonPath("$.error").doesNotExist());
        assertThat(review()).contains("eight kilos of chicken");
    }

    @Test
    void confirmingSavesOneCountPerRowWithTheNoteVoiceCount() throws Exception {
        mockMvc.perform(record());

        mockMvc.perform(confirm()
                        .param("rows[0].quantity", "6")
                        .param("rows[1].quantity", "8")
                        .param("rows[2].quantity", "30")
                        .param("extras[0].itemId", salt.getId().toString())
                        .param("extras[0].quantity", "3"))
                .andExpect(redirectedUrl("/ship/stores/count"))
                .andExpect(flash().attribute("successMessage", "stores.voice.saved"));

        List<StockMovement> movements = movements().stream()
                .sorted(Comparator.comparing(m -> m.getItem().getName())).toList();
        assertThat(movements).hasSize(4).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(StockMovementType.COUNT);
            assertThat(m.getNote()).isEqualTo("Voice count");
            assertThat(m.getBy().getId()).isEqualTo(cook.getId());
        });
        assertThat(movements).extracting(m -> m.getItem().getName() + " " + plain(m.getQuantityBefore()) + " -> "
                        + plain(m.getQuantityAfter()))
                .containsExactly("Chicken breast 30 -> 8", "Eggs 0 -> 30", "Salt 15 -> 3", "Tomatoes 12 -> 6");
        assertThat(levels()).containsExactlyInAnyOrderEntriesOf(Map.of("Tomatoes", "6", "Chicken breast", "8",
                "Eggs", "30", "Salt", "3"));
        // The proposal is gone.
        assertThat(session.getAttribute(VoiceCountController.SESSION_KEY)).isNull();
        mockMvc.perform(get(VOICE).with(user(new CurrentUser(cook))).session(session))
                .andExpect(redirectedUrl("/ship/stores/count"));
    }

    @Test
    void aRemovedRowAndADroppedPhraseAreNotSaved() throws Exception {
        mockMvc.perform(record());

        mockMvc.perform(confirm()
                        .param("rows[0].quantity", "5")
                        .param("rows[1].quantity", "8").param("rows[1].removed", "true")
                        .param("rows[2].quantity", "30")
                        .param("extras[0].itemId", ""))
                .andExpect(redirectedUrl("/ship/stores/count"));

        assertThat(movements()).extracting(m -> m.getItem().getName())
                .containsExactlyInAnyOrder("Tomatoes", "Eggs");
        assertThat(levels()).containsEntry("Chicken breast", "30");
    }

    @Test
    void aWrongQuantityOrAnItemTwiceSavesNothingAndKeepsWhatWasTyped() throws Exception {
        mockMvc.perform(record());

        String html = mockMvc.perform(confirm()
                        .param("rows[0].quantity", "-2")
                        .param("rows[1].quantity", "8")
                        .param("rows[2].quantity", "30"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(row(html, "Tomatoes")).contains("value=\"-2\"", "is-invalid",
                "Enter a quantity of zero or more, with at most 3 decimals.");

        html = mockMvc.perform(confirm()
                        .param("rows[0].quantity", "5")
                        .param("rows[1].quantity", "8")
                        .param("rows[2].quantity", "30")
                        .param("extras[0].itemId", tomatoes.getId().toString())
                        .param("extras[0].quantity", "3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Tomatoes is counted twice. Remove one of them.");

        html = mockMvc.perform(confirm()
                        .param("rows[0].removed", "true").param("rows[1].removed", "true")
                        .param("rows[2].removed", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Nothing to save: every row is removed. Discard the count instead.");

        assertThat(movements()).isEmpty();
        assertThat(session.getAttribute(VoiceCountController.SESSION_KEY)).isNotNull();
    }

    @Test
    void discardThrowsTheProposalAway() throws Exception {
        mockMvc.perform(record());

        mockMvc.perform(post(VOICE + "/discard").with(user(new CurrentUser(cook))).with(csrf()).session(session))
                .andExpect(redirectedUrl("/ship/stores/count"))
                .andExpect(flash().attribute("successMessage", "stores.voice.discarded"));

        assertThat(session.getAttribute(VoiceCountController.SESSION_KEY)).isNull();
        assertThat(movements()).isEmpty();
        mockMvc.perform(confirm().param("rows[0].quantity", "5"))
                .andExpect(redirectedUrl("/ship/stores/count"))
                .andExpect(flash().attribute("errorMessage", "stores.voice.expired"));
    }

    @Test
    void failuresSayWhatHappenedAndLeaveTheStockAlone() throws Exception {
        speech.fail(SpeechToTextException.Reason.TIMEOUT);
        mockMvc.perform(record().accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("That took too long. Nothing was saved. Try again."))
                .andExpect(jsonPath("$.retry").value(true));

        speech.reset();
        extractor.fail(CountExtractionException.Reason.PROVIDER_ERROR);
        mockMvc.perform(record().accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(
                        "The recording could not be processed right now. Nothing was saved. Try again in a moment."))
                .andExpect(jsonPath("$.retry").value(true));

        extractor.reset();
        speech.answer("Good morning, chef.");
        mockMvc.perform(record().accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("No items could be made out. Nothing was saved. Record again, "
                        + "naming each item with its quantity."))
                .andExpect(jsonPath("$.retry").value(false));

        speech.answer("");
        mockMvc.perform(record())
                .andExpect(redirectedUrl("/ship/stores/count"))
                .andExpect(flash().attribute("errorMessage", "stores.voice.failure.NOTHING_UNDERSTOOD"));

        assertThat(session.getAttribute(VoiceCountController.SESSION_KEY)).isNull();
        assertThat(movements()).isEmpty();
        assertThat(levels()).containsExactlyInAnyOrderEntriesOf(Map.of("Tomatoes", "12", "Chicken breast", "30",
                "Salt", "15"));
    }

    @Test
    void onlyARecordingIsAccepted() throws Exception {
        mockMvc.perform(multipart(VOICE).file(new MockMultipartFile("audio", "notes.txt", "text/plain",
                                "five tomatoes".getBytes(StandardCharsets.UTF_8)))
                        .accept(MediaType.APPLICATION_JSON)
                        .with(user(new CurrentUser(cook))).with(csrf()).session(session))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("The recording did not arrive. Record it again."));
        mockMvc.perform(multipart(VOICE).accept(MediaType.APPLICATION_JSON)
                        .with(user(new CurrentUser(cook))).with(csrf()).session(session))
                .andExpect(status().isUnprocessableEntity());
        assertThat(speech.calls()).isEmpty();
    }

    @Test
    void theCountPageOffersTheMicrophoneAndAPendingReview() throws Exception {
        String html = page("/ship/stores/count");
        assertThat(html).contains("data-voice hidden", "Count by voice", ">Enable microphone</button>",
                        "data-voice-hold disabled", "Hold to speak", "data-too-short=\"Hold the button while you speak.\"",
                        "action=\"/ship/stores/count/voice\"", "name=\"_csrf\"", "/js/stores-voice-")
                .doesNotContain("A voice count is waiting for your review.");

        mockMvc.perform(record());
        assertThat(page("/ship/stores/count")).contains("A voice count is waiting for your review.",
                "href=\"/ship/stores/count/voice\"");
    }

    @Test
    void onlyTheChiefCook() throws Exception {
        mockMvc.perform(multipart(VOICE).file(audio()).with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(VOICE).with(user(new CurrentUser(master)))).andExpect(status().isForbidden());
        assertThat(speech.calls()).isEmpty();
    }

    private MockMultipartHttpServletRequestBuilder record() {
        return multipart(VOICE).file(audio()).with(user(new CurrentUser(cook))).with(csrf()).session(session);
    }

    private static MockMultipartFile audio() {
        return new MockMultipartFile("audio", "count.webm", "audio/webm;codecs=opus", new byte[] {26, 69, -33, -93, 1});
    }

    private MockHttpServletRequestBuilder confirm() {
        return post(VOICE + "/confirm").with(user(new CurrentUser(cook))).with(csrf()).session(session);
    }

    private String review() throws Exception {
        return mockMvc.perform(get(VOICE).with(user(new CurrentUser(cook))).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url).with(user(new CurrentUser(cook))).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // The <li> of the review whose item is name.
    private static String row(String html, String name) {
        int at = html.indexOf("class=\"voice-row-name\"");
        while (at >= 0) {
            int start = html.lastIndexOf("<li", at);
            int end = html.indexOf("</li>", at);
            String li = html.substring(start, end);
            if (li.contains(">" + name + "</span>")) {
                return li;
            }
            at = html.indexOf("class=\"voice-row-name\"", end);
        }
        throw new AssertionError("No row for " + name);
    }

    private List<StockMovement> movements() {
        return stockService.history(ship, null, null, null, 0).getContent();
    }

    private Map<String, String> levels() {
        return levelRepository.findAll().stream().filter(l -> l.getItem().getShip().getId().equals(ship.getId()))
                .collect(Collectors.toMap(l -> l.getItem().getName(), l -> plain(l.getQuantity())));
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private void stock(Item item, String quantity) {
        StockLevel level = new StockLevel();
        level.setItem(item);
        level.setQuantity(new BigDecimal(quantity));
        level.setUpdatedAt(Instant.now());
        levelRepository.save(level);
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        return userService.createUser(email, name, "secret123", rank.role(), s, rank);
    }

    private Item item(Ship s, String name, String unit, ItemCategory category) {
        Item item = new Item();
        item.setShip(s);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(category);
        item.setCreatedAt(Instant.now());
        return itemRepository.save(item);
    }

    private Ship ship(String imo, String name) {
        Ship s = new Ship();
        s.setImoNumber(imo);
        s.setName(name);
        s.setCreatedAt(Instant.now());
        return shipRepository.save(s);
    }
}

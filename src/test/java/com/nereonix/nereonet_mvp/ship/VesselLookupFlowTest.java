package com.nereonix.nereonet_mvp.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.vesseldata.FakeVesselApiClient;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselApiClient.Fetch;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselApiResult;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselDataSnapshot;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselDataSnapshotRepository;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselEndpoint;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselLookup;

/**
 * P45: VesselAPI with the fake client (app.vesselapi.fake). "Look up by IMO" on Add vessel (invalid number, already in
 * the fleet, prefill, a failing call next to working ones, flags that disagree), the token checks on Save, "Refresh
 * from VesselAPI" on Edit vessel (only the ticked fields change), a failed refresh keeping the stored data, and the
 * Vessel data tab and Overview line reading the stored rows without calling the provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.vesselapi.fake=true")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class VesselLookupFlowTest {

    private static final Pattern TOKEN = Pattern.compile("name=\"lookupToken\" value=\"([0-9a-f-]{36})\"");
    private static final String IMO = "9328716";
    private static final String CONFLICT_IMO = "9074729";

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired VesselDataSnapshotRepository snapshotRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired FakeVesselApiClient vesselApi;
    @Autowired VesselLookupStore lookupStore;

    @BeforeEach
    void setUp() {
        vesselApi.reset();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Add vessel: Look up by IMO
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theAddVesselPageOffersTheLookupWithoutJavaScript() throws Exception {
        String list = html(get("/office/ships"));
        assertThat(list).contains("href=\"/office/ships/new\"", "formaction=\"/office/ships/new/lookup\"",
                "Look up by IMO");
        String page = html(get("/office/ships/new"));
        assertThat(page).contains("formaction=\"/office/ships/new/lookup\"", "name=\"page\" value=\"true\"")
                .doesNotContain("is not configured");
    }

    @Test
    void anInvalidNumberIsAFieldErrorAndNoCall() throws Exception {
        String html = html(lookup("IMO 9328717").param("name", "Typed name"));
        assertThat(html).contains("Enter a valid IMO number", "value=\"Typed name\"");
        assertThat(vesselApi.calls()).isEmpty();
    }

    @Test
    void aNumberAlreadyInTheFleetIsBlockedWithALink() throws Exception {
        Ship existing = vessel("MV Aegean Spirit", IMO);
        String html = html(lookup("IMO " + IMO));
        assertThat(html).contains("Already in the fleet: MV Aegean Spirit",
                "href=\"/office/ships/" + existing.getId() + "\"");
        assertThat(vesselApi.calls()).isEmpty();
        // Saving it anyway is refused the same way.
        String save = html(post("/office/ships/new").param("page", "true").param("imoNumber", IMO)
                .param("name", "Again").with(csrf()));
        assertThat(save).contains("Already in the fleet: MV Aegean Spirit");
        assertThat(shipRepository.count()).isEqualTo(1);
    }

    @Test
    void theLookupPrefillsTheFormAndSaveKeepsWhatTheUserAccepted() throws Exception {
        String html = html(lookup("IMO 9328716"));
        // The three calls, each once; the provider values next to the mapped ones.
        assertThat(vesselApi.calls()).containsExactlyInAnyOrder("PARTICULARS " + IMO, "POSITION " + IMO, "ETA " + IMO);
        assertThat(html).contains("VesselAPI result", "value=\"AEGEAN TRADER\"", "Cargo · Bulk Carrier",
                        "value=\"BULK_CARRIER\" selected", "value=\"Malta (MT)\"", "Bound for NL RTM",
                        "37.9421° N, 23.6107° E")
                .doesNotContain("Choose the flag");
        assertThat(auditDetails("VESSEL_LOOKUP")).singleElement().asString()
                .contains("IMO " + IMO, "particulars OK", "position OK", "eta OK");
        // Nothing stored before Save.
        assertThat(snapshotRepository.count()).isZero();

        // The user changes the name and saves.
        mockMvc.perform(post("/office/ships/new").param("page", "true").param("imoNumber", IMO)
                        .param("name", "Aegean Trader").param("type", "BULK_CARRIER").param("flag", "Malta (MT)")
                        .param("lookupToken", token(html)).with(csrf()))
                .andExpect(redirectedUrl("/office/ships"));
        Ship ship = shipRepository.findByImoNumber(IMO).orElseThrow();
        assertThat(ship.getName()).isEqualTo("Aegean Trader");
        assertThat(ship.getType()).isEqualTo(ShipType.BULK_CARRIER);
        assertThat(ship.getFlag()).isEqualTo("MT");
        Map<VesselEndpoint, VesselDataSnapshot> rows = rows(ship);
        assertThat(rows).containsOnlyKeys(VesselEndpoint.values());
        assertThat(rows.get(VesselEndpoint.PARTICULARS).getRawJson()).contains("\"teu\": null", "OCEAN PIONEER");
        assertThat(rows.get(VesselEndpoint.POSITION).getProviderReportedAt()).isNotNull();
        assertThat(rows.get(VesselEndpoint.PARTICULARS).getProviderReportedAt()).isNull();
        assertThat(rows.values()).allSatisfy(row -> {
            assertThat(row.getLastAttemptResult()).isEqualTo(VesselApiResult.OK);
            assertThat(row.getLastSuccessAt()).isEqualTo(row.getLastAttemptAt());
        });
        // The name was typed (case aside it is VesselAPI's, so it counts as taken); type and flag as proposed.
        assertThat(auditDetails("VESSEL_DATA_ACCEPTED")).singleElement().asString()
                .contains("IMO " + IMO, "from VesselAPI: name, type, flag");
    }

    @Test
    void oneFailingCallNeverStopsTheOthers() throws Exception {
        vesselApi.answer(VesselEndpoint.POSITION, IMO, Fetch.failed(VesselApiResult.RATE_LIMITED));
        vesselApi.answer(VesselEndpoint.ETA, IMO, Fetch.failed(VesselApiResult.UNAUTHORIZED));
        String html = html(lookup(IMO));
        assertThat(html).contains("value=\"AEGEAN TRADER\"", "VesselAPI limit reached, try later",
                "VesselAPI access problem");

        vesselApi.answer(VesselEndpoint.PARTICULARS, IMO, Fetch.failed(VesselApiResult.TIMEOUT));
        vesselApi.answer(VesselEndpoint.ETA, IMO, Fetch.failed(VesselApiResult.NOT_FOUND));
        html = html(lookup(IMO).param("name", "Kept"));
        assertThat(html).contains("VesselAPI temporarily unavailable", "Enter the details by hand",
                "No ETA report available", "value=\"Kept\"");
    }

    @Test
    void flagsThatDisagreeAreNeverPickedSilently() throws Exception {
        String html = html(lookup(CONFLICT_IMO));
        assertThat(html).contains("the country is Greece and the country code is CY", "Greece (GR)", "Cyprus (CY)",
                "name=\"flagChoice\"");
        assertThat(html).containsPattern("id=\"flag\" name=\"flag\" value=\"\"");
        String token = token(html);

        // No choice: an error, the values kept.
        String error = html(post("/office/ships/new").param("page", "true").param("imoNumber", CONFLICT_IMO)
                .param("name", "Demo").param("lookupToken", token).with(csrf()));
        assertThat(error).contains("Choose the flag: VesselAPI gives two different ones.", "value=\"Demo\"");
        assertThat(shipRepository.findByImoNumber(CONFLICT_IMO)).isEmpty();

        mockMvc.perform(post("/office/ships/new").param("page", "true").param("imoNumber", CONFLICT_IMO)
                        .param("name", "Demo").param("flagChoice", "CY").param("lookupToken", token).with(csrf()))
                .andExpect(redirectedUrl("/office/ships"));
        assertThat(shipRepository.findByImoNumber(CONFLICT_IMO).orElseThrow().getFlag()).isEqualTo("CY");
    }

    @Test
    void saveRejectsALookupForAnotherNumberOrUser() throws Exception {
        String token = token(html(lookup(IMO)));
        // The user typed another (valid) IMO number after the lookup.
        String html = html(post("/office/ships/new").param("page", "true").param("imoNumber", "9074729")
                .param("name", "Typed").param("lookupToken", token).with(csrf()));
        assertThat(html).contains("The VesselAPI result is for IMO 9328716", "value=\"Typed\"");
        assertThat(shipRepository.count()).isZero();

        // Another user's token is unknown to this one.
        String foreign = lookupStore.put(-1L, new VesselLookup(IMO, Instant.now(), Map.of()));
        html = html(post("/office/ships/new").param("page", "true").param("imoNumber", IMO)
                .param("name", "Typed").param("lookupToken", foreign).with(csrf()));
        assertThat(html).contains("The VesselAPI result is no longer available");
        assertThat(shipRepository.count()).isZero();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Edit vessel: Refresh from VesselAPI
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void refreshShowsSavedAgainstVesselApiAndOnlyTickedFieldsChange() throws Exception {
        Ship ship = vessel("Old Name", IMO);
        ship.setFlag("GR");
        String edit = html(get("/office/ships/" + ship.getId() + "/edit"));
        assertThat(edit).contains("formaction=\"/office/ships/" + ship.getId() + "/edit/lookup\"",
                "Refresh from VesselAPI");

        String html = html(post("/office/ships/" + ship.getId() + "/edit/lookup").param("name", "Typed name")
                .param("type", "TANKER").param("flag", "Greece (GR)").with(csrf()));
        assertThat(html).contains("Saved / VesselAPI", "AEGEAN TRADER", "value=\"Typed name\"",
                "name=\"accept\" value=\"name\"", "name=\"accept\" value=\"flag\"", "Malta (MT)");
        // The stored data is updated now; the vessel itself is not.
        assertThat(rows(ship)).containsOnlyKeys(VesselEndpoint.values());
        assertThat(ship.getName()).isEqualTo("Old Name");
        assertThat(auditDetails("VESSEL_DATA_REFRESHED")).singleElement().asString().contains("IMO " + IMO);

        // Only the flag ticked: the name stays as typed, the flag becomes VesselAPI's.
        mockMvc.perform(post("/office/ships/" + ship.getId() + "/edit").param("page", "true")
                        .param("name", "Typed name").param("type", "TANKER").param("flag", "Greece (GR)")
                        .param("accept", "flag").param("lookupToken", token(html)).with(csrf()))
                .andExpect(redirectedUrl("/office/ships"));
        assertThat(ship.getName()).isEqualTo("Typed name");
        assertThat(ship.getType()).isEqualTo(ShipType.TANKER);
        assertThat(ship.getFlag()).isEqualTo("MT");
        assertThat(auditDetails("VESSEL_DATA_ACCEPTED")).singleElement().asString()
                .contains("IMO " + IMO, "from VesselAPI: flag");
    }

    @Test
    void theVesselDataTabRefreshesWithoutFieldsAndATokenForAnotherVesselIsRefused() throws Exception {
        Ship ship = vessel("Aegean Trader", IMO);
        String html = html(post("/office/ships/" + ship.getId() + "/edit/lookup").param("tab", "vessel").with(csrf()));
        assertThat(html).contains("value=\"Aegean Trader\"", "name=\"tab\" value=\"vessel\"");

        Ship other = vessel("Other", CONFLICT_IMO);
        String refused = html(post("/office/ships/" + other.getId() + "/edit").param("page", "true")
                .param("name", "Other").param("accept", "name").param("lookupToken", token(html)).with(csrf()));
        assertThat(refused).contains("The VesselAPI result is for IMO 9328716");
        assertThat(other.getName()).isEqualTo("Other");
    }

    @Test
    void aFailedRefreshKeepsTheStoredDataAndSaysSo() throws Exception {
        Ship ship = vessel("Aegean Trader", IMO);
        refresh(ship);
        VesselDataSnapshot before = rows(ship).get(VesselEndpoint.PARTICULARS);
        String raw = before.getRawJson();
        Instant fetched = before.getLastSuccessAt();

        vesselApi.answer(VesselEndpoint.PARTICULARS, IMO, Fetch.failed(VesselApiResult.RATE_LIMITED));
        refresh(ship);
        VesselDataSnapshot after = rows(ship).get(VesselEndpoint.PARTICULARS);
        assertThat(after.getRawJson()).isEqualTo(raw);
        assertThat(after.getLastSuccessAt()).isEqualTo(fetched);
        assertThat(after.getLastAttemptResult()).isEqualTo(VesselApiResult.RATE_LIMITED);

        String tab = html(get("/office/ships/" + ship.getId() + "/vessel"));
        assertThat(tab).contains("Refresh failed at", "rate limit reached", "AEGEAN TRADER");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The Vessel data tab and the Overview line
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theVesselDataTabShowsTheStoredDataWithoutCallingVesselApi() throws Exception {
        Ship ship = vessel("Aegean Trader", IMO);
        assertThat(html(get("/office/ships/" + ship.getId() + "/vessel"))).contains("No VesselAPI data yet");
        refresh(ship);
        vesselApi.reset();

        String tab = html(get("/office/ships/" + ship.getId() + "/vessel"));
        assertThat(vesselApi.calls()).isEmpty();
        assertThat(tab).contains("Vessel data", "Identity", "248123000", "9HA1234", "Valletta", "Technical", "2006",
                        "225 m", "32 m", "40,562", "76,596 t", "9,480 kW", "5S60MC-C", "Provider statistics",
                        "over about the last 31 days", "11.4 kn", "14.1 m", "Last AIS position", "At anchor",
                        "212.4°", "210°", "Destination (declared by the vessel)", "NL RTM", "NLRTM", "12.4 m",
                        "Provider time", "fetched", "Full VesselAPI response", "&quot;vesselPosition&quot;")
                .doesNotContain("Possible position error", "Refresh failed");
        // Identical former names once.
        assertThat(tab.split("OCEAN PIONEER \\(until 2014\\)", -1)).hasSize(2);
        assertThat(tab).contains("PIONEER (until 2009)");

        String overview = html(get("/office/ships/" + ship.getId()));
        assertThat(vesselApi.calls()).isEmpty();
        assertThat(overview).contains("Last AIS position 2 hours ago", "Bound for NL RTM", "ETA",
                "href=\"/office/ships/" + ship.getId() + "/vessel\"");
    }

    @Test
    void aDoubtfulPositionAndMissingReportsAreShownInWords() throws Exception {
        Ship ship = vessel("Demo", CONFLICT_IMO);
        ship.setFlag("GR");
        refresh(ship);
        String tab = html(get("/office/ships/" + ship.getId() + "/vessel"));
        assertThat(tab).contains("Possible position error (flagged by VesselAPI)", "35.5131° S, 24.0181° W",
                "No ETA report available", "The saved flag was not changed");
        // Technical rows without a value: a muted dash, "Not given" for screen readers.
        assertThat(tab).contains("Not given");
        assertThat(ship.getFlag()).isEqualTo("GR");

        // Greek office screens.
        assertThat(html(get("/office/ships/" + ship.getId() + "/vessel").param("lang", "el")))
                .contains("Στοιχεία πλοίου", "Πιθανό σφάλμα θέσης", "Δεν υπάρχει αναφορά ETA");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder lookup(String imo) {
        return post("/office/ships/new/lookup").param("imoNumber", imo).with(csrf());
    }

    private void refresh(Ship ship) throws Exception {
        mockMvc.perform(post("/office/ships/" + ship.getId() + "/edit/lookup").with(csrf()))
                .andExpect(status().isOk());
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String token(String html) {
        Matcher matcher = TOKEN.matcher(html);
        assertThat(matcher.find()).as("lookup token in the form").isTrue();
        return matcher.group(1);
    }

    private Map<VesselEndpoint, VesselDataSnapshot> rows(Ship ship) {
        return snapshotRepository.findByShip(ship).stream()
                .collect(Collectors.toMap(VesselDataSnapshot::getEndpoint, row -> row));
    }

    private List<String> auditDetails(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action))
                .map(AuditEntry::getDetails).toList();
    }

    private Ship vessel(String name, String imo) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setType(ShipType.BULK_CARRIER);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }
}

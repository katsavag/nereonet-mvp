package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Answers instead of VesselAPI when app.vesselapi.fake is true (P45): dev screens without a key, and the tests. Two
 * vessels are known out of the box: IMO 9328716 (every call answers) and the demo vessel's 9074729 (its flag's
 * country and country code disagree, its position is flagged as a possible error, and it has no ETA report). Any
 * other IMO number is a 404. Tests set their own answers with answer() and read calls().
 */
@Component
@ConditionalOnProperty(name = "app.vesselapi.fake", havingValue = "true")
public class FakeVesselApiClient implements VesselApiClient {

    private final Map<String, Fetch> answers = new ConcurrentHashMap<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Fetch fetch(VesselEndpoint endpoint, String imo) {
        calls.add(endpoint + " " + imo);
        Fetch answer = answers.get(endpoint + " " + imo);
        if (answer != null) {
            return answer;
        }
        String json = builtIn(endpoint, imo);
        return json == null ? Fetch.failed(VesselApiResult.NOT_FOUND) : Fetch.ok(json);
    }

    /** From now on, this call for this IMO number answers so (tests). */
    public void answer(VesselEndpoint endpoint, String imo, Fetch fetch) {
        answers.put(endpoint + " " + imo, fetch);
    }

    /** Every call so far, as "POSITION 9328716". */
    public List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Back to the built-in answers, no calls. */
    public void reset() {
        answers.clear();
        calls.clear();
    }

    private static String builtIn(VesselEndpoint endpoint, String imo) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (imo.equals("9328716")) {
            return switch (endpoint) {
                case PARTICULARS -> """
                        {"vessel": {"imo": 9328716, "mmsi": 248123000, "name": "AEGEAN TRADER",
                         "name_ais": "AEGEAN TRADER", "call_sign": "9HA1234", "vessel_type": "Cargo",
                         "vessel_subtype": "Bulk Carrier", "operating_status": "Active", "country": "Malta",
                         "country_code": "MT", "home_port": "Valletta", "year_built": 2006, "length": 225,
                         "length_unit": "m", "breadth": 32, "breadth_unit": "m", "gross_tonnage": 40562,
                         "deadweight_tonnage": 76596, "engine_type": 1, "engine_model_name": "5S60MC-C",
                         "kilowatt_power": 9480, "speed_calculated_avg": 11.4, "speed_observed_max": 14.2,
                         "draught_calculated_avg": 10.6, "draught_observed_max": 14.1, "summer_draught": 14.2,
                         "teu": null, "former_names": [{"name": "OCEAN PIONEER", "year_until": "2014"},
                                          {"name": "OCEAN PIONEER", "year_until": "2014"},
                                          {"name": "PIONEER", "year_until": "2009"}]}}""";
                case POSITION -> """
                        {"vesselPosition": {"imo": 9328716, "mmsi": 248123000, "vessel_name": "AEGEAN TRADER",
                         "latitude": 37.9421, "longitude": 23.6107, "timestamp": "%s",
                         "processed_timestamp": "%s", "suspected_glitch": false, "sog": 0.1, "cog": 212.4,
                         "heading": 210, "nav_status": 1},
                         "positionCheck": {"exceeds_typical_speed": false, "identity_match": true}}"""
                        .formatted(now.minus(2, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS));
                case ETA -> """
                        {"vesselEta": {"imo": 9328716, "mmsi": 248123000, "vessel_name": "AEGEAN TRADER",
                         "destination": "NL RTM", "destination_port": "NLRTM", "draught": 12.4,
                         "eta": "%s", "timestamp": "%s"}}"""
                        .formatted(now.plus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS),
                                now.minus(5, ChronoUnit.HOURS));
            };
        }
        if (imo.equals("9074729")) {
            return switch (endpoint) {
                case PARTICULARS -> """
                        {"vessel": {"imo": 9074729, "mmsi": 241000000, "name": "DEMO", "vessel_type": "Tanker",
                         "vessel_subtype": "Crude Oil Tanker", "country": "Greece", "country_code": "CY",
                         "year_built": 1995, "length": 182, "length_unit": "m", "breadth": 32,
                         "breadth_unit": "m", "gross_tonnage": 25000, "deadweight_tonnage": 45000}}""";
                case POSITION -> """
                        {"vesselPosition": {"imo": 9074729, "latitude": -35.5131, "longitude": -24.0181,
                         "timestamp": "%s", "suspected_glitch": true, "sog": 13.0, "cog": 90.0, "heading": null,
                         "nav_status": 0}, "positionCheck": {"exceeds_typical_speed": true}}"""
                        .formatted(now.minus(30, ChronoUnit.MINUTES));
                case ETA -> null;
            };
        }
        return null;
    }
}

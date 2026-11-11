package com.nereonix.nereonet_mvp.crewimport;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.nereonix.nereonet_mvp.core.ship.Ship;

// One-off: writes samples/crew-sample.xlsx for trying and demoing the crew import. Skipped in normal test runs.
// Run: ./mvnw test -Dtest=CrewSampleFileGenerator -DgenerateSample=true
class CrewSampleFileGenerator {

    @Test
    @EnabledIfSystemProperty(named = "generateSample", matches = "true")
    void writeSample() throws Exception {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        // The dev seed ship (see DevData).
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");

        // Invented people; example.com is reserved for examples.
        String imo = ship.getImoNumber();
        List<List<String>> rows = List.of(
                List.of("Stavros Pelagidis", "stavros.pelagidis@example.com", "Master", imo),
                List.of("Ramil Dumaguete", "ramil.dumaguete@example.com", "Chief Cook", imo),
                List.of("Kostas Marinakos", "kostas.marinakos@example.com", "Chief Officer", imo),
                List.of("Jerome Bantayan", "jerome.bantayan@example.com", "Second Officer", imo),
                List.of("Nestor Alcaraz", "nestor.alcaraz@example.com", "Bosun", imo),
                List.of("Ivan Petrenkov", "ivan.petrenkov@example.com", "Able Seaman", imo),
                List.of("Dimitris Arvanitakis", "dimitris.arvanitakis@example.com", "Chief Engineer", imo),
                List.of("Mihai Codreanov", "mihai.codreanov@example.com", "Second Engineer", imo),
                List.of("Arnel Pagbilao", "arnel.pagbilao@example.com", "Oiler", imo),
                List.of("Rodel Tagaytay", "rodel.tagaytay@example.com", "Steward", imo));

        Path target = Path.of("samples", "crew-sample.xlsx");
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            CrewTemplate.write(out, rows, List.of(ship), messages, Locale.ENGLISH);
        }
    }
}

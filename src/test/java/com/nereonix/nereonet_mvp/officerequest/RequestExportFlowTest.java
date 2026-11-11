package com.nereonix.nereonet_mvp.officerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestLineRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class RequestExportFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    ItemRequestRepository requestRepository;

    @Autowired
    RequestLineRepository lineRepository;

    @Autowired
    UserService userService;

    @Test
    void officeUserExportsApprovedRequestAsCsv() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User cook = userService.createUser("cook@example.com", "Jane Cook", "secret123", Role.CHIEF_COOK, ship, null);
        User master = userService.createUser("master@example.com", "John Master", "secret123", Role.MASTER, ship, null);

        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(cook);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedBy(master);
        request.setDecidedAt(Instant.parse("2026-11-10T09:30:00Z"));
        request.setCreatedAt(Instant.parse("2026-11-10T08:00:00Z"));
        requestRepository.save(request);
        addLine(request, item(ship, "Basmati rice", "kg"), "25.500");
        addLine(request, item(ship, "Eggs", "pcs"), "120");

        mockMvc.perform(get("/office/requests"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Request #" + request.getId())));
        mockMvc.perform(get("/office/requests/{id}", request.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Basmati rice")));

        byte[] body = mockMvc.perform(post("/office/requests/export")
                        .param("ids", request.getId().toString())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn().getResponse().getContentAsByteArray();

        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿");
        assertThat(csv.substring(1).split("\r\n")).containsExactly(
                "imo_number,ship_name,request_id,approved_at,approved_by,item_name,unit,quantity",
                "9074729,MV Demo," + request.getId() + ",2026-11-10T09:30:00Z,John Master,Basmati rice,kg,25.5",
                "9074729,MV Demo," + request.getId() + ",2026-11-10T09:30:00Z,John Master,Eggs,pcs,120");

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getExportedAt()).isNotNull();
    }

    private Item item(Ship ship, String name, String unit) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit(unit);
        item.setCreatedAt(Instant.now());
        return itemRepository.save(item);
    }

    private void addLine(ItemRequest request, Item item, String quantity) {
        RequestLine line = new RequestLine();
        line.setRequest(request);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        request.getLines().add(lineRepository.save(line));
    }
}

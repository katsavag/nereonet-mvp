package com.nereonix.nereonet_mvp.mycrew;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.nereonix.nereonet_mvp.core.profile.PhotoResponse;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// My crew (P31): who is on board now and what each chose to share. Read only, English only, every ship role
// (SecurityConfig: /ship/** is for ship roles). The pages load no script of their own.
@Controller
public class MyCrewController {

    private final MyCrewService myCrewService;

    public MyCrewController(MyCrewService myCrewService) {
        this.myCrewService = myCrewService;
    }

    @GetMapping("/ship/crew")
    public String list(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        model.addAttribute("crew", myCrewService.list(currentUser.getUsername()));
        return "mycrew/list";
    }

    @GetMapping("/ship/crew/{id}")
    public String crewmate(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        model.addAttribute("person", myCrewService.crewmate(currentUser.getUsername(), id));
        return "mycrew/crewmate";
    }

    // size: 512 or 96 (anything else is a 404).
    @GetMapping("/ship/crew/{id}/photo/{key}/{size}")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                          @PathVariable String key, @PathVariable String size) {
        return PhotoResponse.of(myCrewService.photo(currentUser.getUsername(), id, key, PhotoResponse.size(size)));
    }
}

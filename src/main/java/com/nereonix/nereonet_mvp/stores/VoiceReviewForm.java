package com.nereonix.nereonet_mvp.stores;

import java.util.ArrayList;
import java.util.List;

// The review of a voice count (P57, POST /ship/stores/count/voice/confirm), by position in the session's Proposal:
// rows[i] is the proposal's line i (its item comes from the session, never from the form), extras[j] its unmatched
// phrase j. A row: the quantity counted (editable) and removed. An extra: the item picked (blank: dropped) and its
// quantity. Quantities are text, so a wrong one comes back as typed.
public class VoiceReviewForm {

    private List<RowInput> rows = new ArrayList<>();
    private List<ExtraInput> extras = new ArrayList<>();

    public static class RowInput {

        private String quantity;
        private boolean removed;

        public String getQuantity() {
            return quantity;
        }

        public void setQuantity(String quantity) {
            this.quantity = quantity;
        }

        public boolean isRemoved() {
            return removed;
        }

        public void setRemoved(boolean removed) {
            this.removed = removed;
        }
    }

    public static class ExtraInput {

        private Long itemId;
        private String quantity;

        public Long getItemId() {
            return itemId;
        }

        public void setItemId(Long itemId) {
            this.itemId = itemId;
        }

        public String getQuantity() {
            return quantity;
        }

        public void setQuantity(String quantity) {
            this.quantity = quantity;
        }
    }

    /** The form as first shown: every line kept with the quantity heard, every phrase dropped. */
    static VoiceReviewForm of(VoiceCountService.Proposal proposal) {
        VoiceReviewForm form = new VoiceReviewForm();
        proposal.lines().forEach(line -> {
            RowInput row = new RowInput();
            row.setQuantity(line.counted().stripTrailingZeros().toPlainString());
            form.rows.add(row);
        });
        proposal.unmatched().forEach(phrase -> form.extras.add(new ExtraInput()));
        return form;
    }

    /** The form as posted, one row per line and one extra per phrase of the proposal: a row missing from the post is
     *  kept with what was heard, a missing extra is dropped, anything beyond the proposal is ignored. */
    VoiceReviewForm fitTo(VoiceCountService.Proposal proposal) {
        VoiceReviewForm fitted = of(proposal);
        for (int i = 0; i < fitted.rows.size() && i < rows.size(); i++) {
            if (rows.get(i) != null) {
                fitted.rows.set(i, rows.get(i));
            }
        }
        for (int j = 0; j < fitted.extras.size() && j < extras.size(); j++) {
            if (extras.get(j) != null) {
                fitted.extras.set(j, extras.get(j));
            }
        }
        return fitted;
    }

    public List<RowInput> getRows() {
        return rows;
    }

    public void setRows(List<RowInput> rows) {
        this.rows = rows;
    }

    public List<ExtraInput> getExtras() {
        return extras;
    }

    public void setExtras(List<ExtraInput> extras) {
        this.extras = extras;
    }
}

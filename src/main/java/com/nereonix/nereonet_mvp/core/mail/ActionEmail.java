package com.nereonix.nereonet_mvp.core.mail;

import java.util.List;

/**
 * The content of an email that asks for one action through a link (P29): an invitation, a password reset. Every text
 * is final, in the email's language; templates/email/action.html lays it out (HtmlEmails).
 *   language     "en" or "el", the html lang
 *   subject      also the HTML title
 *   preheader    the line a mail client shows after the subject in the inbox
 *   heading      the card's heading
 *   paragraphs   what happened and what to do, one paragraph each (the first is the greeting)
 *   buttonLabel  the one button: "Accept invitation"
 *   url          the link of the button, shown again as text under it
 *   linkHint     the line above the link as text
 *   expiry       when the link stops working, in UTC
 *   footer       who sent it, why the person got it, and the support contact when there is one
 */
public record ActionEmail(String language, String subject, String preheader, String heading, List<String> paragraphs,
                          String buttonLabel, String url, String linkHint, String expiry, List<String> footer) {
}

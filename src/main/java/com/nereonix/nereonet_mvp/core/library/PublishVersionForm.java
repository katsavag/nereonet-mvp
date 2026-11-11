package com.nereonix.nereonet_mvp.core.library;

import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The "Publish new version" form, used by the Master (library) and the office (officelibrary).
// Same content rules as LibraryItemForm: text OR one file. LibraryService checks that the label is new.
public class PublishVersionForm {

    @NotBlank(message = "{library.error.versionLabelRequired}")
    @Size(max = LibraryItemVersion.VERSION_LABEL_MAX_LENGTH, message = "{library.error.tooLong}")
    private String versionLabel;

    private String text;

    private MultipartFile file;

    @AssertTrue(message = "{library.error.content}")
    public boolean isContentValid() {
        return StringUtils.hasText(text) != hasFile();
    }

    @AssertTrue(message = "{library.error.fileType}")
    public boolean isFileTypeAllowed() {
        return !hasFile() || fileContentType() != null;
    }

    @AssertTrue(message = "{library.error.fileTooLarge}")
    public boolean isFileSizeAllowed() {
        return !hasFile() || file.getSize() <= LibraryItemForm.MAX_FILE_SIZE;
    }

    public boolean hasFile() {
        return file != null && !file.isEmpty();
    }

    public String fileContentType() {
        return LibraryItemForm.contentTypeOf(file);
    }

    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel == null ? null : versionLabel.strip(); }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }
}

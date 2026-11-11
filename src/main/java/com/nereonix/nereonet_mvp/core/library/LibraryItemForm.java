package com.nereonix.nereonet_mvp.core.library;

import java.util.Locale;
import java.util.Map;

import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The "Add an item" form, used by the Master (library) and the office (officelibrary).
// The content is text OR one file. The multipart limits in application.yml match MAX_FILE_SIZE.
public class LibraryItemForm {

    public static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // Allowed file extensions and the content type each one must be sent with.
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @NotBlank(message = "{library.error.titleRequired}")
    @Size(max = LibraryItem.TITLE_MAX_LENGTH, message = "{library.error.tooLong}")
    private String title;

    @NotNull(message = "{library.error.categoryRequired}")
    private LibraryCategory category;

    @NotBlank(message = "{library.error.versionLabelRequired}")
    @Size(max = LibraryItemVersion.VERSION_LABEL_MAX_LENGTH, message = "{library.error.tooLong}")
    private String versionLabel;

    @NotNull
    private Boolean required = false;

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
        return !hasFile() || file.getSize() <= MAX_FILE_SIZE;
    }

    // For the file input (data-max-size), which warns before the upload.
    public long getMaxFileSize() {
        return MAX_FILE_SIZE;
    }

    public boolean hasFile() {
        return file != null && !file.isEmpty();
    }

    /** The content type for the file's extension, if the file was sent with it; otherwise null. */
    public String fileContentType() {
        return contentTypeOf(file);
    }

    /** The content type for the file's extension, if the file was sent with it; otherwise null. Also used by PublishVersionForm. */
    static String contentTypeOf(MultipartFile file) {
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String expected = extension == null ? null : CONTENT_TYPES.get(extension.toLowerCase(Locale.ROOT));
        return expected != null && expected.equalsIgnoreCase(file.getContentType()) ? expected : null;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null ? null : title.strip(); }

    public LibraryCategory getCategory() { return category; }
    public void setCategory(LibraryCategory category) { this.category = category; }

    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel == null ? null : versionLabel.strip(); }

    public Boolean getRequired() { return required; }
    public void setRequired(Boolean required) { this.required = required; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }
}

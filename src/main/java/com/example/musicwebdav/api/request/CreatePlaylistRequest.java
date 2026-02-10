package com.example.musicwebdav.api.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePlaylistRequest {

    @NotBlank
    @Size(max = 128)
    private String name;
}

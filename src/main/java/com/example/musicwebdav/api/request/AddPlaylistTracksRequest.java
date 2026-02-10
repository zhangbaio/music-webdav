package com.example.musicwebdav.api.request;

import java.util.List;
import javax.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class AddPlaylistTracksRequest {

    @NotEmpty
    private List<Long> trackIds;
}

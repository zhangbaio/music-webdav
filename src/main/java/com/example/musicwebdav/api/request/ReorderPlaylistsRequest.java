package com.example.musicwebdav.api.request;

import java.util.List;
import javax.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class ReorderPlaylistsRequest {

    @NotEmpty
    private List<Long> playlistIds;
}

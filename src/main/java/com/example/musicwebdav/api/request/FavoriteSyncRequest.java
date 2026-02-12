package com.example.musicwebdav.api.request;

import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FavoriteSyncRequest {

    @NotNull
    private Boolean targetFavorite;

    private Long expectedVersion;

    private String idempotencyKey;
}

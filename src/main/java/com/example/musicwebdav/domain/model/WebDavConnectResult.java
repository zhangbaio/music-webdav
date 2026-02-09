package com.example.musicwebdav.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebDavConnectResult {

    private boolean success;

    private String message;
}

package com.athuull.hera.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request payload containing a list of tracks to be batched and downloaded.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTracksRequest {
    private List<Track> tracks;
}
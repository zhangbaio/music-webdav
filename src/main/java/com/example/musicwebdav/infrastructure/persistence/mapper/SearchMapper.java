package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.model.SearchAlbumRow;
import com.example.musicwebdav.infrastructure.persistence.model.SearchArtistRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SearchMapper {

    List<TrackEntity> selectSongPageAll(@Param("offset") int offset,
                                        @Param("pageSize") int pageSize,
                                        @Param("keyword") String keyword);

    long countSongAll(@Param("keyword") String keyword);

    List<TrackEntity> selectSongPageMine(@Param("offset") int offset,
                                         @Param("pageSize") int pageSize,
                                         @Param("keyword") String keyword);

    long countSongMine(@Param("keyword") String keyword);

    List<SearchArtistRow> selectArtistPage(@Param("offset") int offset,
                                           @Param("pageSize") int pageSize,
                                           @Param("keyword") String keyword);

    long countArtists(@Param("keyword") String keyword);

    List<SearchAlbumRow> selectAlbumPage(@Param("artist") String artist,
                                         @Param("offset") int offset,
                                         @Param("pageSize") int pageSize,
                                         @Param("keyword") String keyword);

    long countAlbums(@Param("artist") String artist,
                     @Param("keyword") String keyword);

    List<TrackEntity> selectArtistTrackPage(@Param("artist") String artist,
                                            @Param("offset") int offset,
                                            @Param("pageSize") int pageSize,
                                            @Param("keyword") String keyword);

    long countArtistTracks(@Param("artist") String artist,
                           @Param("keyword") String keyword);

    String selectExactArtist(@Param("keyword") String keyword);

    String selectFuzzyArtist(@Param("keyword") String keyword);

    long countArtistTracksByName(@Param("artist") String artist);
}

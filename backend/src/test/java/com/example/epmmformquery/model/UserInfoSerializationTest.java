package com.example.epmmformquery.model;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserInfoSerializationTest {

    @Test
    void serializedUserInfoNeverContainsATokenField() throws Exception {
        // Compile-level guard: the record must not even have an accessToken component.
        assertThat(Arrays.stream(UserInfo.class.getRecordComponents())
                .map(c -> c.getName()))
                .doesNotContain("accessToken");

        UserInfo me = new UserInfo("leo", "u-1", "leo@example.com",
                "Leo T", "Leo", "T", List.of("admin"));
        String json = new ObjectMapper().writeValueAsString(me);
        assertThat(json).doesNotContain("accessToken");
    }
}

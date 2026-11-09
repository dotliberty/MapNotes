package ru.main.explorer;

import java.io.IOException;

/** One bounded, sequential server round before MapPack backs off or subdivides. */
final class Overpass {
    static final String[] ENDPOINTS = {
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        MapPack.ENDPOINT,
        "https://overpass.private.coffee/api/interpreter"
    };

    interface Request<T> {
        T fetch(String endpoint) throws Exception;
    }

    static <T> T fetch(Request<T> request) throws Exception {
        IOException last = null;
        boolean allPermanent = true, allLimited = true;
        for (String endpoint : ENDPOINTS)
            try {
                return request.fetch(endpoint);
            } catch (MapPack.PausedException paused) {
                throw paused;
            } catch (MapPack.TooLargeException dense) {
                throw dense;
            } catch (IOException failure) {
                last = failure;
                allPermanent &= failure instanceof MapPack.PermanentException;
                allLimited &= failure instanceof MapPack.RateLimitException;
            }
        if (allPermanent || allLimited) throw last;
        throw new MapPack.QueryFailure(
                Messages.text("all_map_servers_are_unavailable") + last.getMessage(), last);
    }
}

package dev.apiguard;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TargetPolicy {
  private final PlatformProperties properties;

  public TargetPolicy(PlatformProperties properties) {
    this.properties = properties;
  }

  public String validate(String baseUrl) {
    try {
      URI uri = URI.create(baseUrl);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || uri.getPort() > 65535
          || uri.getPort() == 0) throw new IllegalArgumentException();
      if (properties.allowedHosts().stream()
          .noneMatch(h -> h.trim().equalsIgnoreCase(uri.getHost())))
        throw ApiException.badRequest(
            "Target host is not in API_ALLOWED_HOSTS: " + uri.getHost().toLowerCase(Locale.ROOT));
      return baseUrl.replaceAll("/+$", "");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.badRequest(
          "baseUrl must be an HTTP(S) URL without credentials, query or fragment");
    }
  }
}

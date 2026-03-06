using Microsoft.Azure.Functions.Worker;
using Microsoft.Azure.Functions.Worker.Http;
using Microsoft.Extensions.Logging;
using Microsoft.Identity.Web;
using System.Net;
using System.Text;
using System.Text.Json;
using MSAL = Microsoft.Identity.Client;

namespace DeviceRegistration;

public class DeviceRegistrationFunction(ILogger<DeviceRegistrationFunction> logger, IHttpClientFactory httpClientFactory, RateLimiterService rateLimiter)
{
    // ── ICP / ANH configuration — set these in Azure Function App Settings ────
    private static readonly string PartnerId = Environment.GetEnvironmentVariable("PARTNER_ID") ?? "IrisStudioCore";
    private static readonly string AnhAccountName = Environment.GetEnvironmentVariable("ANH_ACCOUNT_NAME") ?? "IrisMobileAndroidANH";
    private static readonly string PartnerTenantId = Environment.GetEnvironmentVariable("PARTNER_TENANT_ID") ?? "cdc5aeea-15c5-4db6-b079-fcadd2505dc2";
    private static readonly string PartnerClientId = Environment.GetEnvironmentVariable("PARTNER_CLIENT_ID") ?? "a244d522-7dae-4c17-b377-574077bae6b4";
    private static readonly string ManagedIdentityClientId = Environment.GetEnvironmentVariable("MANAGED_IDENTITY_CLIENT_ID") ?? "6293a03f-aa3d-41b6-81f8-2ee2ea752e27";
    private static readonly string RegisterUrl = "https://mucp.api.account.microsoft.com/applications/v2/anhregister";
    private static readonly List<string> PartnerScope = new List<string>() { "https://mucp.api.account.microsoft.com/.default" };
    // ─────────────────────────────────────────────────────────────────────────

    [Function("RegisterDeviceWithANH")]
    public async Task<HttpResponseData> RegisterDeviceWithANH(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "register")] HttpRequestData req)
    {
        logger.LogInformation("RegisterDeviceWithANH triggered");

        // 1. Deserialize request body
        RegistrationRequest? body;
        try
        {
            body = await JsonSerializer.DeserializeAsync<RegistrationRequest>(
                req.Body,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        }
        catch (JsonException ex)
        {
            logger.LogWarning("Invalid JSON body: {Error}", ex.Message);
            return await BadRequest(req, "Invalid JSON body.");
        }

        if (string.IsNullOrWhiteSpace(body?.FcmToken)) return await BadRequest(req, "fcmToken is required.");
        if (string.IsNullOrWhiteSpace(body?.UserId)) return await BadRequest(req, "userId is required.");
        if (string.IsNullOrWhiteSpace(body?.InstallationId)) return await BadRequest(req, "installationId is required.");
        if (string.IsNullOrWhiteSpace(body?.Platform)) return await BadRequest(req, "platform is required.");
        if (body.Platform is not "FcmV1" and not "Apns") return await BadRequest(req, "platform must be 'FcmV1' (Android) or 'Apns' (iOS).");

        //Add rate limit
        var clientIp = GetClientIp(req);
        if (!rateLimiter.IsAllowed(clientIp))
        {
            logger.LogWarning("Rate limit exceeded for IP: {ClientIp}", clientIp);
            return await TooManyRequests(req, "Too many registration requests. Please try again later.");
        }

        var locale = string.IsNullOrWhiteSpace(body.Locale) ? "en-US" : body.Locale;

        // 2. Acquire partner token via Workload Identity Federation:
        //    MI acts as a federated credential for the partner app registration.
        //    Step 1 — get an MI token (used as the client assertion).
        //    Step 2 — exchange it via ClientAssertionCredential for a token
        //             scoped to the ICP partner resource.
        string partnerToken;
        try
        {
            var builder = MSAL.ConfidentialClientApplicationBuilder.Create(PartnerClientId);

            ManagedIdentityClientAssertion managedIdentityClientAssertion = new ManagedIdentityClientAssertion(ManagedIdentityClientId);
            MSAL.IConfidentialClientApplication clientApp = builder
                .WithClientAssertion((MSAL.AssertionRequestOptions options) =>
                {
                    return managedIdentityClientAssertion.GetSignedAssertion(default);
                })
                .Build();

            var authResult = await clientApp
                .AcquireTokenForClient(PartnerScope)
                .WithTenantId(PartnerTenantId)
                .ExecuteAsync().ConfigureAwait(false);

            partnerToken = authResult.AccessToken;

            logger.LogInformation("Partner token acquired via Workload Identity Federation (MI → app registration)");
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to acquire partner token");
            return await InternalError(req, $"Failed to acquire partner token.{ex}");
        }

        // 3. Call ICP ANH registration API
        var correlationId = Guid.NewGuid().ToString();
        var clientRequestId = Guid.NewGuid().ToString();

        var anhPayload = new
        {
            accountName = AnhAccountName,
            platform = body.Platform,
            installationId = body.InstallationId,
            handle = body.FcmToken,
            locale = locale,
            userId = body.UserId
        };

        logger.LogInformation(
            "ANH payload — accountName: {AccountName}, platform: {Platform}, installationId: {InstallationId}, userId: {UserId}, locale: {Locale}",
            AnhAccountName, body.Platform, body.InstallationId, body.UserId, locale);

        var authHeader = $"Partner partner_id=\"{PartnerId}\", bearer_token=\"{partnerToken}\"";

        var anhRequest = new HttpRequestMessage(HttpMethod.Post, RegisterUrl)
        {
            Content = new StringContent(JsonSerializer.Serialize(anhPayload), Encoding.UTF8, "application/json")
        };
        anhRequest.Headers.TryAddWithoutValidation("Authorization", authHeader);
        anhRequest.Headers.Add("MS-CV", correlationId);
        anhRequest.Headers.Add("client-request-id", clientRequestId);

        HttpResponseMessage anhResponse;
        try
        {
            anhResponse = await httpClientFactory.CreateClient().SendAsync(anhRequest);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "ANH registration HTTP call failed");
            return await InternalError(req, "ANH registration call failed.");
        }

        var responseBody = await anhResponse.Content.ReadAsStringAsync();
        logger.LogInformation("ANH response — status: {Status}, requestId: {RequestId}, body: {Body}",
            (int)anhResponse.StatusCode,
            anhResponse.Headers.TryGetValues("request-id", out var ids) ? ids.FirstOrDefault() : "n/a",
            responseBody);

        if (anhResponse.IsSuccessStatusCode)
        {
            var ok = req.CreateResponse(HttpStatusCode.OK);
            await ok.WriteAsJsonAsync(new { message = "Device registered successfully." });
            return ok;
        }

        var err = req.CreateResponse((HttpStatusCode)anhResponse.StatusCode);
        await err.WriteAsJsonAsync(new { error = $"ANH registration failed ({(int)anhResponse.StatusCode}): {responseBody}" });
        return err;
    }

    private static async Task<HttpResponseData> BadRequest(HttpRequestData req, string message)
    {
        var response = req.CreateResponse(HttpStatusCode.BadRequest);
        await response.WriteAsJsonAsync(new { error = message });
        return response;
    }

    /// <summary>
    /// Extracts the real client IP from X-Forwarded-For (set by Azure's load balancer).
    /// X-Forwarded-For can be a comma-separated list — the first entry is the original client.
    /// Falls back to "unknown" if the header is absent.
    /// </summary>
    private static string GetClientIp(HttpRequestData req)
    {
        if (req.Headers.TryGetValues("X-Forwarded-For", out var values))
        {
            var forwarded = values.FirstOrDefault();
            if (!string.IsNullOrWhiteSpace(forwarded))
                return forwarded.Split(',')[0].Trim();
        }
        return "unknown";
    }

    private static async Task<HttpResponseData> TooManyRequests(HttpRequestData req, string message)
    {
        var response = req.CreateResponse(HttpStatusCode.TooManyRequests);
        response.Headers.Add("Retry-After", "86400");
        await response.WriteAsJsonAsync(new { error = message });
        return response;
    }

    private static async Task<HttpResponseData> InternalError(HttpRequestData req, string message)
    {
        var response = req.CreateResponse(HttpStatusCode.InternalServerError);
        await response.WriteAsJsonAsync(new { error = message });
        return response;
    }
}

public record RegistrationRequest(
    string? FcmToken,
    string? UserId,
    string? InstallationId,
    string? Platform,
    string? Locale);

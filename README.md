# Swagger validator for kotlin models
Detekt plugin that validates a swagger spec with the models defined in your code

### Configuration:
##### Step 1: Update build.gradle
```groovy
dependencies {
    //Runtime required to annotate your code with the swagger definitions
    implementation "com.icapps.swaggervalidator:swaggervalidator-runtime:${swaggerValidatorRuntimeVersion}"

    //Detekt plugin
    detektPlugins "com.icapps.swaggervalidator:swaggervalidator-detekt:${swaggerValidatorVersion}"
}
```

#### Step 2: Update detekt configuration
```yaml
SwaggerValidator:
  active: true
  swaggerUrl: '<link to swagger json definition>'
  swaggerVersion: 2 //V2 only for now
```

#### Step 3: Annotate json models
```kotlin
@SwaggerModel(name = "RefreshTokenRequest")
data class TokenRefreshRequestBody(val refreshToken: String)

@SwaggerModel(name = "RefreshTokenResponse")
data class TokenRefreshRequestBody(@Json(name="token") val accessToken: String,
                                    val type: TokenType)

@SwaggerEnumModel
enum class TokenType {
    @field:Json(name="full")
    FULL,
    @field:Json(name="partial")
    PARTIAL
}
```

#### Step 4: Run detekt
Note: Due to current limitations of detekt, some sanity checks can only happen in a post-processing step. In this step,
we can no longer cleanly generate detekt warnings. In this case, we currently print the issues and exit detekt with an error code
package io.seqera.tower.plugin

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import groovy.json.JsonOutput
import io.seqera.tower.plugin.exception.UnauthorizedException
import nextflow.Global
import nextflow.Session
import nextflow.SysEnv
import nextflow.exception.AbortOperationException
import nextflow.fusion.FusionConfig
import spock.lang.Shared
import spock.lang.Specification

import java.time.temporal.ChronoUnit

/**
 * Test cases for the TowerFusionEnv class.
 *
 * @author Alberto Miranda <alberto.miranda@seqera.io>
 */
class TowerFusionEnvTest extends Specification {

    @Shared
    WireMockServer wireMockServer

    def setupSpec() {
        wireMockServer = new WireMockServer(18080)
        wireMockServer.start()
    }

    def cleanupSpec() {
        wireMockServer.stop()
    }

    def setup() {
        wireMockServer.resetAll()
        SysEnv.push([:])  // <-- ensure the system host env does not interfere
    }

    def cleanup() {
        SysEnv.pop()      // <-- restore the system host env
    }


    def 'should return the endpoint from the config'() {
        given: 'a session'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint: 'https://tower.nf'
                ]
            ]
        }

        when: 'the provider is created'
        def provider = new TowerFusionEnv()

        then: 'the endpoint has the expected value'
        provider.endpoint == 'https://tower.nf'
    }

    def 'should return the endpoint from the environment'() {
        setup:
        SysEnv.push(['TOWER_API_ENDPOINT': 'https://tower.nf'])
        Global.session = Mock(Session) {
            config >> [:]
        }

        when: 'the provider is created'
        def provider = new TowerFusionEnv()

        then: 'the endpoint has the expected value'
        provider.endpoint == 'https://tower.nf'

        cleanup:
        SysEnv.pop()
    }

    def 'should return the default endpoint'() {
        when: 'session config is empty'
        Global.session = Mock(Session) {
            config >> [
                tower: [:]
            ]
        }
        def provider = new TowerFusionEnv()

        then: 'the endpoint has the expected value'
        provider.endpoint == TowerClient.DEF_ENDPOINT_URL

        when: 'session config is null'
        Global.session = Mock(Session) {
            config >> null
        }

        then: 'the endpoint has the expected value'
        provider.endpoint == TowerClient.DEF_ENDPOINT_URL

        when: 'session config is missing'
        Global.session = Mock(Session) {
            config >> [:]
        }

        then: 'the endpoint has the expected value'
        provider.endpoint == TowerClient.DEF_ENDPOINT_URL

        when: 'session.config.tower.endpoint is not defined'
        Global.session = Mock(Session) {
            config >> [
                tower: [:]
            ]
        }

        then: 'the endpoint has the expected value'
        provider.endpoint == TowerClient.DEF_ENDPOINT_URL

        when: 'session.config.tower.endpoint is null'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint: null
                ]
            ]
        }

        then: 'the endpoint has the expected value'

        when: 'session.config.tower.endpoint is empty'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint: ''
                ]
            ]
        }

        then: 'the endpoint has the expected value'

        when: 'session.config.tower.endpoint is defined as "-"'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint: '-'
                ]
            ]
        }

        then: 'the endpoint has the expected value'
    }

    def 'should return the access token from the config'() {
        given: 'a session'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    accessToken: 'abc123'
                ]
            ]
        }

        when: 'the provider is created'
        def provider = new TowerFusionEnv()

        then: 'the access token has the expected value'
        provider.accessToken == 'abc123'
    }

    def 'should return the access token from the environment'() {
        setup:
        Global.session = Mock(Session) {
            config >> [:]
        }
        SysEnv.push(['TOWER_ACCESS_TOKEN': 'abc123'])

        when: 'the provider is created'
        def provider = new TowerFusionEnv()

        then: 'the access token has the expected value'
        provider.accessToken == 'abc123'

        cleanup:
        SysEnv.pop()
    }

    def 'should prefer the access token from the config'() {
        setup:
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    accessToken: 'abc123'
                ]
            ]
        }
        SysEnv.push(['TOWER_ACCESS_TOKEN': 'xyz789'])

        when: 'the provider is created'
        def provider = new TowerFusionEnv()

        then: 'the access token has the expected value'
        provider.accessToken == 'abc123'

        cleanup:
        SysEnv.pop()
    }

    def 'should prefer the access token from the config despite being null'() {
        setup:
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    accessToken: null
                ]
            ]
        }
        SysEnv.push(['TOWER_ACCESS_TOKEN': 'xyz789'])

        when: 'the provider is created'
        def provider = new TowerFusionEnv()

        then: 'the access token has the expected value'
        provider.accessToken == null

        cleanup:
        SysEnv.pop()
    }

    def 'should prefer the access token from the environment if TOWER_WORKFLOW_ID is set'() {
        setup:
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    accessToken: 'abc123'
                ]
            ]
        }
        SysEnv.push(['TOWER_ACCESS_TOKEN' : 'xyz789', 'TOWER_WORKFLOW_ID': '123'])

        when: 'the provider is created'
        def provider = new TowerFusionEnv()

        then: 'the access token has the expected value'
        provider.accessToken == 'xyz789'

        cleanup:
        SysEnv.pop()
    }

    def 'should get a license token'() {
        given: 'a TowerFusionEnv provider'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint   : 'http://localhost:18080',
                    accessToken: 'abc123'
                ]
            ]
        }
        def provider = new TowerFusionEnv()

        and: 'a mock endpoint returning a valid token'
        final now = new Date().toInstant()
        final expirationDate = JsonOutput.toJson(Date.from(now.plus(1, ChronoUnit.DAYS)))
        wireMockServer.stubFor(
            WireMock.post(WireMock.urlEqualTo("/license/token/"))
                .withHeader('Authorization', WireMock.equalTo('Bearer abc123'))
                .willReturn(
                    WireMock.aResponse()
                        .withStatus(200)
                        .withHeader('Content-Type', 'application/json')
                        .withBody('{"signedToken":"xyz789", "expirationDate":' + expirationDate + '}')
                )
        )

        when: 'a license token is requested'
        final token = provider.getLicenseToken(PRODUCT, VERSION)

        then: 'the token has the expected value'
        token == 'xyz789'

        and: 'the request is correct'
        wireMockServer.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/license/token/"))
            .withHeader('Authorization', WireMock.equalTo('Bearer abc123')))

        where:
        PRODUCT        | VERSION
        'some-product' | 'some-version'
        'some-product' | null
        null           | 'some-version'
        null           | null
    }

    def 'should fail getting a token if the Platform configuration is missing'() {
        given: 'a TowerFusionEnv provider'
        Global.session = Mock(Session) {
            config >> [:]
        }
        def provider = new TowerFusionEnv()

        when: 'a license token is requested'
        provider.getLicenseToken('some-product', 'some-version')

        then: 'an exception is thrown'
        final ex = thrown(AbortOperationException)
        ex.message == 'Missing Platform access token -- Make sure there\'s a variable TOWER_ACCESS_TOKEN in your environment'
    }

    def 'should fail getting a token if the Platform configuration is empty'() {
        given: 'a TowerFusionEnv provider'
        Global.session = Mock(Session) {
            config >> [
                tower: [:]
            ]
        }
        def provider = new TowerFusionEnv()

        when: 'a license token is requested'
        provider.getLicenseToken('some-product', 'some-version')

        then: 'an exception is thrown'
        final ex = thrown(AbortOperationException)
        ex.message == 'Missing Platform access token -- Make sure there\'s a variable TOWER_ACCESS_TOKEN in your environment'
    }

    def 'should fail getting a token if the Platform access token is missing'() {
        given: 'a TowerFusionEnv provider'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint: 'http://localhost:18080'
                ]
            ]
        }
        def provider = new TowerFusionEnv()

        when: 'a license token is requested'
        provider.getLicenseToken('some-product', 'some-version')

        then: 'an exception is thrown'
        final ex = thrown(AbortOperationException)
        ex.message == 'Missing Platform access token -- Make sure there\'s a variable TOWER_ACCESS_TOKEN in your environment'
    }

    def 'should throw UnauthorizedException if getting a token fails with 401'() {
        given: 'a TowerFusionEnv provider'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint   : 'http://localhost:18080',
                    accessToken: 'abc123'
                ]
            ]
        }
        def provider = new TowerFusionEnv()

        and: 'a mock endpoint returning an error'
        wireMockServer.stubFor(
            WireMock.post(WireMock.urlEqualTo("/license/token/"))
                .withHeader('Authorization', WireMock.equalTo('Bearer abc123'))
                .willReturn(
                    WireMock.aResponse()
                        .withStatus(401)
                        .withHeader('Content-Type', 'application/json')
                        .withBody('{"error":"Unauthorized"}')
                )
        )

        when: 'a license token is requested'
        provider.getLicenseToken('some-product', 'some-version')

        then: 'an exception is thrown'
        thrown(UnauthorizedException)
    }

    def 'should return a valid environment' () {
        given: 'a TowerFusionEnv provider'
        Global.session = Mock(Session) {
            config >> [:]
        }
        def provider = Spy(TowerFusionEnv)

        when: 'the environment is requested'
        def env = provider.getEnvironment('s3', Mock(FusionConfig))

        then: 'the environment has the expected values'
        1 * provider.getLicenseToken(_, _) >> 'xyz789'
        env == [FUSION_LICENSE_TOKEN: 'xyz789']
    }

    def 'should return an empty environment if no Platform config is available' () {
        given: 'a session with no config for Platform'
        Global.session = Mock(Session) {
            config >> [:]
        }

        when: 'the environment is requested'
        def provider = new TowerFusionEnv()
        def env = provider.getEnvironment('-', Mock(FusionConfig))

        then: 'the environment is empty'
        env == [:]
    }

    def 'should return an empty environment if the license token cannot be obtained' () {
        given: 'a TowerFusionEnv provider'
        Global.session = Mock(Session) {
            config >> [
                tower: [
                    endpoint   : 'http://localhost:18080',
                    accessToken: 'abc123'
                ]
            ]
        }
        def provider = Spy(TowerFusionEnv)

        when: 'the environment is requested'
        def env = provider.getEnvironment('s3', Mock(FusionConfig))

        then: 'the environment has the expected values'
        1 * provider.getLicenseToken(_, _) >> {
            throw new Exception('error')
        }
        env == [:]
    }
}

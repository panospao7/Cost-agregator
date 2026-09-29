"""Regression evidence for the shared, fail-closed provider-body proof."""
from pathlib import Path
import re
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from guardrails.cloud_payload_proof import CloudProofError, executable_kotlin_source, unproved_post_lines
from kotlin_callable_parser import ParserError, mask_kotlin_source


PREPARE = "val prepared = cloudPayloadPolicy.prepareText(CloudPayloadPurpose.GENERAL, raw)"
POST = 'Request.Builder().post(body.toRequestBody("application/json".toMediaType())).build()'


def source(body, helpers=""):
    return f"""package example
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.yourname.expensetracker.domain.config.AppConfig
import java.util.Base64
class ExampleProvider(private val cloudPayloadPolicy: CloudPayloadPolicy) {{
    suspend fun send(raw: String) {{
        {body}
    }}
    {helpers}
}}
"""


@pytest.mark.parametrize("binding", [
    "val body = prepared.text",
    "val alias = prepared.text\nval body = alias",
    "val alias =\n    prepared.text\nval body = alias",
    'val body = JSONObject().put("text", prepared.text).toString()',
    '''val body = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().put("text", prepared.text)))
        put("temperature", 0.1)
    }.toString()''',
])
def test_direct_preparation_aliases_and_closed_json_builders(binding):
    assert unproved_post_lines(source(PREPARE + "\n" + binding + "\n" + POST)) == []


def test_live_json_serialized_at_the_post_is_not_mistaken_for_an_unknown_escape():
    body = PREPARE + '\nval body = JSONObject().put("text", prepared.text)\n'
    post = POST.replace("body.toRequestBody", "body.toString().toRequestBody")
    assert unproved_post_lines(source(body + post)) == []


def test_nested_json_reference_is_checked_through_its_later_serialization():
    body = PREPARE + '''
    val parts = JSONArray().put(prepared.text)
    val body = JSONObject().put("parts", parts)
    parts.put(raw)
    '''
    post = POST.replace("body.toRequestBody", "body.toString().toRequestBody")
    assert unproved_post_lines(source(body + post))


def test_optional_prepared_json_append_and_boolean_metadata_remain_supported():
    helper = '''private data class Payload(val jsonBody: String, val used: Boolean)
    private fun serialize(prepared: PreparedCloudPayload): Payload {
        val image = if (prepared.rawImageIncluded) {
            JSONObject().put("data", prepared.text)
        } else {
            null
        }
        val parts = JSONArray().put(JSONObject().put("text", prepared.text))
        image?.let(parts::put)
        val json = JSONObject().put("parts", parts).toString()
        return Payload(jsonBody = json, used = image != null)
    }'''
    assert unproved_post_lines(source(PREPARE + "\nval body = serialize(prepared).jsonBody\n" + POST, helper)) == []


def test_called_helper_is_proved_from_returned_value_not_its_name():
    helper = '''private fun arbitrarySerializer(prepared: PreparedCloudPayload): String {
        return JSONObject().put("text", prepared.text).toString()
    }'''
    text = source(PREPARE + "\nval body = arbitrarySerializer(prepared)\n" + POST, helper)
    assert unproved_post_lines(text) == []


def test_inert_record_constructor_is_not_mistaken_for_a_shadowed_builtin():
    helper = '''private data class Payload(val jsonBody: String)
    private fun serialize(prepared: PreparedCloudPayload): Payload {
        return Payload(jsonBody = prepared.text)
    }'''
    assert unproved_post_lines(source(PREPARE + "\nval body = serialize(prepared).jsonBody\n" + POST, helper)) == []


def test_preparation_inside_called_helper_receives_actual_policy_binding():
    helper = '''private suspend fun serialize(raw: String, policy: CloudPayloadPolicy): String {
        val prepared = policy.prepareText(CloudPayloadPurpose.GENERAL, raw)
        return JSONObject().put("text", prepared.text).toString()
    }'''
    assert unproved_post_lines(source("val body = serialize(raw, cloudPayloadPolicy)\n" + POST, helper)) == []


@pytest.mark.parametrize("quote", ['"', '"""'])
@pytest.mark.parametrize("prefix", ["", "approved: "])
def test_string_template_dependencies_are_not_erased_as_constant_literals(quote, prefix):
    template = "$" + "{prepared.text}"
    helper = f'''private fun serialize(prepared: PreparedCloudPayload): String {{
        return {quote}{prefix}{template}{quote}
    }}'''
    text = source(PREPARE + "\nval body = serialize(prepared)\n" + POST, helper)
    assert unproved_post_lines(text) == []
    assert unproved_post_lines(text.replace(template, "$" + "{rawInput}"))


def test_policy_failure_catches_must_terminate_instead_of_returning_fallback_payload():
    prefix = '''val prepared = try {
        cloudPayloadPolicy.prepareText(CloudPayloadPurpose.GENERAL, raw)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return
    }
    val body = prepared.text
    '''
    assert unproved_post_lines(source(prefix + POST)) == []
    assert unproved_post_lines(source(prefix.replace("        return\n", "        fabricatedPayload\n") + POST))


@pytest.mark.parametrize("prefix", [
    "// cloudPayloadPolicy.prepareText(raw)\nval body = raw",
    'val marker = "CloudPayloadPolicy PreparedCloudPayload prepareText"\nval body = raw',
    PREPARE + "\nval body = raw",
    'val prepared = PreparedCloudPayload(raw)\nval body = prepared.text',
    PREPARE + "\nval body = prepared.text + raw",
    PREPARE + "\nval safe = prepared.text\nval body = raw",
    PREPARE + "\nvar body = prepared.text\nbody = raw",
    PREPARE + '\nval json = JSONObject().put("text", prepared.text)\njson.put("extra", raw)\nval body = json.toString()',
    PREPARE + '\nval json = JSONObject().put("text", prepared.text)\nval alias = json\nalias.put("extra", raw)\nval body = json.toString()',
    PREPARE + '\nval json = JSONObject().put("text", prepared.text)\nuntrusted(json)\nval body = json.toString()',
    PREPARE + '\nval body = unknownSerializer(prepared)',
    'val body = "cloudPayloadPolicy.prepareText(raw)"',
])
def test_markers_unused_results_raw_substitution_and_unproved_mutation_fail(prefix):
    assert len(unproved_post_lines(source(prefix + "\n" + POST))) == 1


def test_unrelated_helper_and_dead_local_preparation_cannot_authorize_post():
    helper = '''private suspend fun unused(raw: String): PreparedCloudPayload {
        return cloudPayloadPolicy.prepareText(CloudPayloadPurpose.GENERAL, raw)
    }'''
    assert unproved_post_lines(source("val body = raw\n" + POST, helper))
    local = "fun unused() { " + PREPARE + " }\nval body = raw\n" + POST
    assert unproved_post_lines(source(local))


def test_misleading_builder_name_does_not_authorize_raw_return():
    helper = '''private fun buildRequestBody(prepared: PreparedCloudPayload): String {
        return unapprovedInput
    }'''
    assert unproved_post_lines(source(PREPARE + "\nval body = buildRequestBody(prepared)\n" + POST, helper))


def test_recursive_helper_is_unproved_not_unbounded_recursion():
    helper = '''private fun recursive(prepared: PreparedCloudPayload): String {
        return recursive(prepared)
    }'''
    assert unproved_post_lines(source(PREPARE + "\nval body = recursive(prepared)\n" + POST, helper))


def test_helper_early_raw_return_cannot_hide_behind_final_prepared_return():
    helper = '''private fun serialize(prepared: PreparedCloudPayload): String {
        if (condition) return unapprovedInput
        return prepared.text
    }'''
    assert unproved_post_lines(source(PREPARE + "\nval body = serialize(prepared)\n" + POST, helper))


def test_live_json_helper_parameter_is_not_assumed_immutable():
    helper = '''private fun serialize(json: JSONObject): String {
        json.put("raw", unapprovedInput)
        return json.toString()
    }'''
    body = PREPARE + '\nval json = JSONObject().put("text", prepared.text)\nval body = serialize(json)\n'
    assert unproved_post_lines(source(body + POST, helper))


@pytest.mark.parametrize("escape", [
    'val alias: JSONObject = json\nalias.put("raw", raw)',
    'var alias = (json)\nalias.put("raw", raw)',
    'json.apply { put("raw", raw) }',
    'json.let { it.put("raw", raw) }',
    'put(json)',
    'unknownFactory().put(json)',
])
def test_unproved_mutable_receiver_or_alias_cannot_authorize_original_json(escape):
    body = PREPARE + '\nval json = JSONObject().put("text", prepared.text)\n'
    assert unproved_post_lines(source(body + escape + '\nval body = json.toString()\n' + POST))


@pytest.mark.parametrize("replacement", [
    ("okhttp3.RequestBody.Companion.toRequestBody", "untrusted.toRequestBody"),
    ("com.yourname.expensetracker.domain.config.AppConfig", "untrusted.AppConfig"),
    ("java.util.Base64", "untrusted.Base64"),
])
def test_serialization_and_config_names_require_canonical_imports(replacement):
    body = PREPARE + '''
    val body = JSONObject().put("text", prepared.text)
        .put("limit", AppConfig.Ai.MAX_TOKENS)
        .put("image", Base64.getEncoder().encodeToString(prepared.imageBytes)).toString()
    '''
    text = source(body + POST)
    assert unproved_post_lines(text) == []
    assert unproved_post_lines(text.replace(*replacement))


def test_shadowed_policy_parameter_cannot_borrow_constructor_evidence():
    text = source(PREPARE + "\nval body = prepared.text\n" + POST)
    text = text.replace("send(raw: String)", "send(raw: String, cloudPayloadPolicy: UntrustedPolicy)")
    assert unproved_post_lines(text)


def test_unrelated_type_with_policy_simple_name_is_not_canonical_policy():
    text = source(PREPARE + "\nval body = prepared.text\n" + POST)
    assert unproved_post_lines(text.replace(
        "com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy", "example.other.CloudPayloadPolicy"
    ))


def test_each_post_is_checked_independently():
    text = source(PREPARE + "\nval body = prepared.text\n" + POST + "\n" + POST.replace("body.toRequestBody", "raw.toRequestBody"))
    failures = unproved_post_lines(text)
    assert len(failures) == 1
    assert "raw.toRequestBody" in text.splitlines()[failures[0] - 1]


def test_prepared_metadata_cannot_launder_a_raw_record_body():
    helper = '''private data class Payload(val jsonBody: String, val metadata: String)
    private fun serialize(prepared: PreparedCloudPayload): Payload {
        return Payload(jsonBody = unapprovedInput, metadata = prepared.text)
    }'''
    assert unproved_post_lines(source(PREPARE + "\nval body = serialize(prepared).jsonBody\n" + POST, helper))


@pytest.mark.parametrize("malformed", ['class Broken { val text = "unterminated', "class Broken { /* unfinished"])
def test_malformed_source_is_never_an_empty_compliant_result(malformed):
    with pytest.raises((CloudProofError, ParserError)):
        unproved_post_lines(malformed)


def test_executable_template_projection_preserves_offsets_without_restoring_literal_markers():
    call = 'runCatching { fetch("fake RequestBody.create marker") }'
    template = "$" + "{" + call + "}"
    text = 'val rendered = "literal runGuarded ' + template + '"\n'
    projected = executable_kotlin_source(text)
    assert len(projected) == len(text)
    assert [i for i, char in enumerate(projected) if char == "\n"] == [i for i, char in enumerate(text) if char == "\n"]
    assert "runCatching" in projected
    assert "runGuarded" not in projected
    assert "RequestBody.create" not in projected


def test_executable_post_inside_template_cannot_hide_behind_unused_preparation():
    call = "Request.Builder().post(raw.toRequestBody()).build()"
    template = "$" + "{" + call + "}"
    assert unproved_post_lines(source(PREPARE + '\nval rendered = "' + template + '"'))
    assert unproved_post_lines(source('val documentation = "' + call + '"')) == []


PROVIDERS = (
    "CloudCategorizationAssistService", "CloudDashboardBriefingService",
    "CloudDedupeJudgeService", "CloudQueryInterpretationService",
    "CloudReceiptAssistService", "CloudReceiptItemCategorizationService",
    "CloudReviewExplanationService", "CloudWarrantyExtractionService",
)


@pytest.mark.parametrize("provider", PROVIDERS)
def test_current_real_provider_serialization_paths_have_policy_provenance(provider):
    root = Path(__file__).resolve().parents[2]
    path = root / "app/src/main/java/com/yourname/expensetracker/data/ai/provider" / (provider + ".kt")
    text = path.read_text(encoding="utf-8")
    expected_posts = 2 if provider == "CloudReceiptAssistService" else 1
    assert len(re.findall(r"\.\s*post\s*\(", mask_kotlin_source(text))) == expected_posts
    assert unproved_post_lines(text) == []

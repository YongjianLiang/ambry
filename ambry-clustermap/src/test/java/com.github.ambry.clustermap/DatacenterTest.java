package com.github.ambry.clustermap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

// TestDatacenter permits Datacenter to be constructed with a null HardwareLayout.
class TestDatacenter extends Datacenter {
  public TestDatacenter(JSONObject jsonObject) throws JSONException {
    super(null, jsonObject);
  }

  @Override
  public void validateHardwareLayout() {
    // Null OK.
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    TestDatacenter testDatacenter = (TestDatacenter) o;

    if (!getName().equals(testDatacenter.getName())) return false;
    if (getCapacityGB() != testDatacenter.getCapacityGB()) return false;

    return true;
  }
}

/**
 * Tests {@link Datacenter} class.
 */
public class DatacenterTest {
  private static int diskCount = 10;
  private static long diskCapacityGB = 1000;

  private static int dataNodeCount = 6;

  JSONArray getDisks() throws JSONException {
    return TestUtils.getJsonArrayDisks(diskCount, "/mnt", HardwareState.AVAILABLE, diskCapacityGB);
  }

  JSONArray getDataNodes() throws JSONException {
    return TestUtils.getJsonArrayDataNodes(dataNodeCount, "localhost", 6666, HardwareState.AVAILABLE, getDisks());
  }

  @Test
  public void basics() throws JSONException {
    JSONObject jsonObject = TestUtils.getJsonDatacenter("XYZ1", getDataNodes());

    Datacenter datacenter = new TestDatacenter(jsonObject);

    assertEquals(datacenter.getName(), "XYZ1");
    assertEquals(datacenter.getDataNodes().size(), dataNodeCount);
    assertEquals(datacenter.getCapacityGB(), dataNodeCount * diskCount * diskCapacityGB);
    assertEquals(datacenter.toJSONObject().toString(), jsonObject.toString());
    assertEquals(datacenter, new TestDatacenter(datacenter.toJSONObject()));
  }

  public void failValidation(JSONObject jsonObject) throws JSONException {
    try {
      new TestDatacenter(jsonObject);
      fail("Should have failed validation.");
    } catch (IllegalStateException e) {
      // Expected.
    }
  }

  @Test
  public void validation() throws JSONException {
    JSONObject jsonObject;

    try {
      // Null HardwareLayout
      jsonObject = TestUtils.getJsonDatacenter("XYZ1", getDataNodes());
      new Datacenter(null, jsonObject);
      fail("Should have failed validation.");
    } catch (IllegalStateException e) {
      // Expected.
    }

    // Bad datacenter name
    jsonObject = TestUtils.getJsonDatacenter("", getDataNodes());
    failValidation(jsonObject);
  }
}
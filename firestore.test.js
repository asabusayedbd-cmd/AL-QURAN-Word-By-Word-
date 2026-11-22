const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_noor_123";
const BOB_UID = "bob_noor_456";
const DATABASE_ID = "ai-studio-android-nooralqu-03dd5414-a90a-4734-a65b-15e2af6072d4";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any user subcollection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("bookmarks").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("2_255").set({
      userId: ALICE_UID,
      verseKey: "2:255",
      surahId: 2,
      ayahNumber: 255,
      collectionId: "favorites",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Owner can create, update, and list bookmarks and reject ghost/id fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_2_255").set({
      userId: ALICE_UID,
      verseKey: "2:255",
      surahId: 2,
      ayahNumber: 255,
      collectionId: "favorites",
      label: "Ayat al-Kursi",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_2_255").update({
      collectionId: "hifz",
      label: "Evening protection",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").get()
  );

  // Reject immutable createdAt tamper
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_2_255").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field / @DocumentId id field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_1_1").set({
      id: "bm_1_1",
      userId: ALICE_UID,
      verseKey: "1:1",
      surahId: 1,
      ayahNumber: 1,
      collectionId: "favorites",
      createdAt: created,
      updatedAt: created,
    })
  );
});

test("Cross-user isolation blocks Bob from reading or writing Alice's notes, progress, and streaks", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore(DATABASE_ID);
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1_1").set({
      userId: ALICE_UID,
      verseKey: "1:1",
      surahId: 1,
      ayahNumber: 1,
      noteText: "All praise belongs to Allah.",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("progress").doc("main").set({
      userId: ALICE_UID,
      lastSurahId: 1,
      lastAyahNumber: 7,
      lastVerseKey: "1:7",
      dailyAyahGoal: 20,
      khatamTargetDays: 30,
      khatamCompletedAyahs: 7,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("streaks").doc("2026-11-21").set({
      userId: ALICE_UID,
      dateKey: "2026-11-21",
      ayahsReadCount: 12,
      minutesSpent: 15,
      tasbeehCount: 33,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("notes").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("progress").doc("main").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("streaks").doc("2026-11-21").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1_1").update({
      noteText: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Future timestamp and invalid bounds fail validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("streaks").doc("2026-11-21").set({
      userId: ALICE_UID,
      dateKey: "2026-11-21",
      ayahsReadCount: 10,
      minutesSpent: 10,
      tasbeehCount: 0,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("progress").doc("main").set({
      userId: ALICE_UID,
      lastSurahId: 200,
      lastAyahNumber: 1,
      lastVerseKey: "200:1",
      dailyAyahGoal: 20,
      khatamTargetDays: 30,
      khatamCompletedAyahs: 0,
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

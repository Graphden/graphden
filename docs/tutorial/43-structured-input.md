# 43. Bind part of a record and run with the rest

## Goal

Save one field on a function and supply the rest of its record when you run it.

The interactive exercise requires a deployment that supports structured-input-run
and offers lesson 43 in its editor catalogue. For ordinary Run with slots, see
[lesson 15](15-executing-a-fn.md).

## Concepts

A record groups fields under names. In this lesson the argument `profile`
contains three text fields: `id`, `name` and `email`. The function returns the
profile it receives, so you can see exactly how its input was filled.

A binding at `profile.name` gives that field a saved value. The declared record
still has three fields. The remaining input is the part you can supply when you
run: `profile.id` and `profile.email`. One run's values leave the binding as it
was.

## Try it

1. Start **Bind part of a record and run with the rest** in the editor's
   interactive tutorial from **main**. It opens a separate lesson branch.
   Find `lesson-profile` in the Explorer and select the template from
   `lessons.structured-input`.
2. Open the template's **⋯** menu and choose **Extend**. Name the new function
   `tutorial-structured-profile`, keep the root namespace selected and save.
3. Open **Remaining inputs** and expand **profile**. Click **Bind** beside
   **name**, enter `Ada` and confirm. The lesson checks the saved binding at
   `profile.name`.
4. Click **Run** on the sample's card. Under **profile**, enter `42` as
   **id** and `ada@example.test` as **email**. Submit the form.
5. Check that the result contains all three fields.

   ```json
   {"id": "42", "name": "Ada", "email": "ada@example.test"}
   ```

6. Click **Finish** and use the lesson's cleanup to remove its branch.

If you repeat the exercise, start on a fresh lesson branch so that
`profile.name` is free again. For background on record types, read
[lesson 08](08-types.md).

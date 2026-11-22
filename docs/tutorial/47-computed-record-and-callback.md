# 47. Build a record and supply a callback

## Goal

Bind a function that produces a profile record and a callback that formats
the greeting. Run with one builder input and one renderer input. The
editor offers this lesson when your deployment supports structured-input-run
and can authorize the template and both package functions.

## Try it

1. Open **Build a record and supply a callback** from the editor on **main**.
   Select `lesson-compute.structured/lesson-computed-greeting`.
2. Choose **Extend**. Name the sample `tutorial-computed-greeting`, keep
   the root namespace selected and click **Save**.
3. Under **Remaining inputs**, expand **style** and click **Bind** beside
   **callback**. Choose `lesson-compute.callback/mark-greeting` and confirm.
   The callback has a saved mark binding of `!`.
4. Open **Structured inputs**. Click **Bind** next to **profile**, choose
   **Choose function**, select `lesson-compute.builder/build-greeting-profile`
   and confirm. This binding computes the whole record. Its own **name**
   input is still free.
5. Open **Run**. In the builder’s profile group, enter `Ada` for **name**.
   Enter `team` for the style’s **suffix** and click **Run**.

   ```text
   Hello Ada! · team
   ```

6. Click **Finish** and delete the lesson branch.

The builder uses name to produce the profile. The renderer passes the built
name to the callback at the call site. Its `string` parameter never appears
in the outer Run form. The callback keeps its saved mark, and the suffix
you supply goes directly to the renderer.

# 46. Bind a whole record or one field

## Goal

Save a full profile as one binding, then remove it and bind just the name.
The editor offers this exercise when your deployment supports
structured-input-run and you can extend and run the profile template in a
lesson branch.

## Try it

1. Open **Bind a whole record or one field** from the editor on **main**.
   Select `lessons.structured-input/lesson-profile` in the Explorer.
2. Choose **Extend** from the template's **⋯** menu. Enter
   `tutorial-whole-profile`, keep the root namespace and click **Save**.
3. Open **Remaining inputs** and click **Bind** beside **profile**. Paste
   this JSON and confirm.

   ```json
   {"id":"42","name":"Ada","email":"ada@example.test"}
   ```

4. Open **Structured inputs**. **Bound inputs** shows one profile binding.
   Its descendants are occupied too. A second Bind for profile or one of
   its fields is refused, so the saved record stays intact.
5. Open **Run** and run with no remaining inputs. The result is the whole
   profile you saved.
6. In **Structured inputs**, choose **Remove** beside the profile binding
   and confirm. Open **Remaining inputs**, expand **profile** and bind only
   **name** to `Bea`.
7. Open **Run** and supply id `77` and email `bea@example.test`. The result
   pairs those values with the saved name.
8. Click **Finish** and delete the lesson branch.

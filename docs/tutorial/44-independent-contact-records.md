# 44. Bind two independent contact records

## Goal

Save two values for identically named fields in separate records. Supply
the remaining values in a single Run. See [lesson 43](43-structured-input.md)
for the first exercise on binding part of a record.

The editor offers this exercise when your deployment supports
structured-input-run and your account can extend and run its contact route
template on a lesson branch.

## Try it

1. Open **Bind two independent contact records** from the editor on **main**.
   Select `lesson-contact.structured-input/lesson-contact-route` in the Explorer.
2. Choose **Extend** from the template's **⋯** menu. Enter
   `tutorial-contact-route`, keep the root namespace selected and click **Save**.
3. Open **Remaining inputs**. Expand **sender**, then **address**. Choose
   **Bind** next to **city**, enter `London`, and confirm.
4. Open **Structured inputs** if it is closed. Expand **recipient**, then
   **address**. Bind its city to `Paris`. The two bindings use the paths
   `sender.address.city` and `recipient.address.city`.
5. Open **Run**. Enter `Ada` in **sender → name** and `Bea` in
   **recipient → name**. Click **Run**.
6. Check the result, then choose **Finish** and delete the lesson branch.

   ```text
   Ada in London → Bea in Paris
   ```

The path starts at the input name. Both records have an `address.city` field,
and each path identifies a different value. After you bind both cities, this
Run needs only the two names. The saved cities apply to later runs on the same
lesson function.

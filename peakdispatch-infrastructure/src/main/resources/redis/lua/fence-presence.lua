local generation = tonumber(ARGV[1])
local sequence = tonumber(ARGV[2])
local member = ARGV[3]
local old = redis.call('HGET', KEYS[2], member)
if old then
    local oldGeneration, oldSequence = string.match(old, '^(%d+):(%d+)$')
    oldGeneration = tonumber(oldGeneration)
    oldSequence = tonumber(oldSequence)
    if oldGeneration > generation or (oldGeneration == generation and oldSequence > sequence) then
        return 0
    end
end
redis.call('HSET', KEYS[2], member, generation .. ':' .. sequence)
redis.call('ZREM', KEYS[1], member)
redis.call('ZREM', KEYS[3], member)
return 1
